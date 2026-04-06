package io.streamfence;

import com.corundumstudio.socketio.AckMode;
import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.Transport;
import io.streamfence.internal.transport.NamespaceHandler;
import io.streamfence.internal.security.AuthRateLimiter;
import io.streamfence.internal.security.StaticTokenValidator;
import io.streamfence.internal.transport.PemReloadWatcher;
import io.streamfence.internal.transport.PemTlsMaterialLoader;
import io.streamfence.internal.config.NamespaceConfig;
import io.streamfence.internal.config.ServerConfig;
import io.streamfence.internal.config.TopicPolicy;
import io.streamfence.internal.delivery.ClientSessionRegistry;
import io.streamfence.internal.delivery.ClientSessionState;
import io.streamfence.internal.delivery.TopicDispatcher;
import io.streamfence.internal.observability.ServerEventPublisher;
import io.streamfence.internal.config.SocketIoServerSpecMapper;
import io.streamfence.internal.observability.ManagementHttpServer;
import io.streamfence.internal.delivery.ClientLane;
import io.streamfence.internal.delivery.AckTracker;
import io.streamfence.internal.delivery.RetryService;
import io.streamfence.internal.delivery.TopicRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SocketIoServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SocketIoServer.class);

    private final SocketIoServerSpec spec;
    private final ServerConfig serverConfig;
    private final ServerMetrics metrics;
    private final ClientSessionRegistry sessionRegistry;
    private final TopicRegistry topicRegistry;
    private final TopicDispatcher topicDispatcher;
    private final SocketIOServer socketIOServer;
    private final ScheduledExecutorService scheduler;
    private final AuthRateLimiter authRateLimiter;
    private final ManagementHttpServer managementHttpServer;
    private final PemReloadWatcher pemReloadWatcher;
    private final ServerEventPublisher eventPublisher;

    private SocketIoServer(SocketIoServerSpec spec) {
        this.spec = spec;
        this.serverConfig = SocketIoServerSpecMapper.toServerConfig(spec);
        this.eventPublisher = new ServerEventPublisher(spec.listeners());
        this.metrics = new ServerMetrics();
        this.sessionRegistry = new ClientSessionRegistry();
        this.topicRegistry = new TopicRegistry(serverConfig);
        AckTracker ackTracker = new AckTracker();
        RetryService retryService = new RetryService(ackTracker);

        int senderThreads = serverConfig.senderThreads() > 0
                ? serverConfig.senderThreads()
                : Math.max(4, Runtime.getRuntime().availableProcessors());
        this.topicDispatcher = new TopicDispatcher(
                topicRegistry,
                sessionRegistry,
                ackTracker,
                retryService,
                metrics,
                senderThreads,
                new ObjectMapper(),
                eventPublisher);

        this.authRateLimiter = serverConfig.authMode().name().equals("TOKEN")
                ? new AuthRateLimiter(serverConfig.authRejectMaxPerWindow(), serverConfig.authRejectWindowMs())
                : null;
        TokenValidator tokenValidator = spec.tokenValidator();
        if (tokenValidator == null && serverConfig.authMode().name().equals("TOKEN")) {
            tokenValidator = new StaticTokenValidator(serverConfig.staticTokens());
        }

        this.socketIOServer = new SocketIOServer(toConfiguration(serverConfig));
        registerNamespaces(tokenValidator);

        for (TopicPolicy policy : topicRegistry.allPolicies()) {
            String topic = policy.topic();
            String namespace = policy.namespace();
            metrics.registerTopicGauges(policy, () -> collectLanes(namespace, topic));
        }

        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "wsserver-scheduler");
            thread.setDaemon(true);
            return thread;
        });

        this.managementHttpServer = serverConfig.managementPort() > 0
                ? new ManagementHttpServer(metrics, serverConfig.managementHost(), serverConfig.managementPort())
                : null;

        this.pemReloadWatcher = (serverConfig.transportMode() == TransportMode.WSS && serverConfig.tls() != null)
                ? new PemReloadWatcher(
                        Path.of(serverConfig.tls().certChainPemPath()),
                        Path.of(serverConfig.tls().privateKeyPemPath()),
                        null)
                : null;
    }

    public static SocketIoServerBuilder builder() {
        return new SocketIoServerBuilder();
    }

    public static SocketIoServer create(SocketIoServerSpec spec) {
        return new SocketIoServer(spec);
    }

    public SocketIoServerSpec spec() {
        return spec;
    }

    public void start() {
        eventPublisher.serverStarting(serverConfig.host(), serverConfig.port(), serverConfig.managementPort());
        socketIOServer.start();
        scheduler.scheduleAtFixedRate(topicDispatcher::processRetries, 500, 500, TimeUnit.MILLISECONDS);
        if (authRateLimiter != null) {
            long windowMillis = serverConfig.authRejectWindowMs();
            scheduler.scheduleAtFixedRate(
                    () -> authRateLimiter.reap(System.currentTimeMillis()),
                    windowMillis, windowMillis, TimeUnit.MILLISECONDS);
        }
        if (pemReloadWatcher != null) {
            scheduler.scheduleAtFixedRate(pemReloadWatcher::check, 30, 30, TimeUnit.SECONDS);
        }
        if (managementHttpServer != null) {
            try {
                managementHttpServer.start();
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to start management HTTP server", exception);
            }
        }
        eventPublisher.serverStarted(serverConfig.host(), serverConfig.port(), managementPort());
    }

    public void stop() {
        close();
    }

    public ServerMetrics metrics() {
        return metrics;
    }

    /**
     * Broadcasts a payload to every subscriber of {@code topic} on
     * {@code namespace}. Honors the topic's delivery mode, queue limits, and
     * overflow policy.
     *
     * @throws IllegalArgumentException if the namespace or topic is unknown
     */
    public void publish(String namespace, String topic, Object payload) {
        topicDispatcher.publish(namespace, topic, payload);
    }

    /**
     * Sends a payload to a single connected client that is subscribed to
     * {@code topic}. Silently no-ops if the client is not connected; emits a
     * warn log and returns if the client is connected but not subscribed.
     */
    public void publishTo(String namespace, String clientId, String topic, Object payload) {
        topicDispatcher.publishTo(namespace, clientId, topic, payload);
    }

    public int managementPort() {
        return managementHttpServer == null ? -1 : managementHttpServer.boundPort();
    }

    @Override
    public void close() {
        eventPublisher.serverStopping(serverConfig.host(), serverConfig.port(), managementPort());
        long deadline = System.currentTimeMillis() + Math.max(1, serverConfig.shutdownDrainMs());

        if (managementHttpServer != null) {
            try {
                managementHttpServer.stop(2);
            } catch (RuntimeException exception) {
                LOG.warn("event=shutdown_step_failed phase=management_http error={}", exception.getMessage());
            }
        }

        CompletableFuture<Void> socketStopFuture = CompletableFuture.runAsync(() -> {
            try {
                socketIOServer.stop();
            } catch (RuntimeException exception) {
                LOG.warn("event=shutdown_step_failed phase=socket_io_server error={}", exception.getMessage());
            }
        }, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "wsserver-shutdown-stopper");
            thread.setDaemon(true);
            return thread;
        }));
        try {
            long budget = Math.max(0, deadline - System.currentTimeMillis());
            socketStopFuture.get(budget, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            LOG.warn("event=shutdown_timeout phase=socketIOServer drainMs={}", serverConfig.shutdownDrainMs());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException exception) {
            LOG.warn("event=shutdown_step_failed phase=socket_io_server error={}", exception.getCause().getMessage());
        }

        scheduler.shutdown();
        try {
            long remaining = Math.max(0, deadline - System.currentTimeMillis());
            long bounded = Math.min(5000, remaining);
            if (!scheduler.awaitTermination(bounded, TimeUnit.MILLISECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }

        topicDispatcher.close();
        eventPublisher.serverStopped(serverConfig.host(), serverConfig.port(), managementPort());
    }

    private List<ClientLane> collectLanes(String namespace, String topic) {
        List<ClientLane> lanes = new ArrayList<>();
        for (ClientSessionState session : sessionRegistry.subscribersOf(namespace, topic)) {
            ClientLane lane = session.lane(topic);
            if (lane != null) {
                lanes.add(lane);
            }
        }
        return lanes;
    }

    private void registerNamespaces(TokenValidator tokenValidator) {
        for (Map.Entry<String, NamespaceConfig> entry : serverConfig.namespaces().entrySet()) {
            String namespace = entry.getKey();
            new NamespaceHandler(
                    namespace,
                    entry.getValue(),
                    serverConfig,
                    topicRegistry,
                    sessionRegistry,
                    topicDispatcher,
                    tokenValidator,
                    metrics,
                    authRateLimiter,
                    eventPublisher
            ).register(socketIOServer.addNamespace(namespace));
        }
    }

    private Configuration toConfiguration(ServerConfig config) {
        Configuration configuration = new Configuration();
        configuration.setHostname(config.host());
        configuration.setPort(config.port());
        configuration.setPingInterval(config.pingIntervalMs());
        configuration.setPingTimeout(config.pingTimeoutMs());
        configuration.setMaxFramePayloadLength(config.maxFramePayloadLength());
        configuration.setMaxHttpContentLength(config.maxHttpContentLength());
        configuration.setHttpCompression(config.compressionEnabled());
        configuration.setWebsocketCompression(config.compressionEnabled());
        configuration.setEnableCors(config.enableCors());
        configuration.setPreferDirectBuffer(false);
        configuration.setAckMode(AckMode.MANUAL);
        configuration.setTransports(Transport.WEBSOCKET, Transport.POLLING);
        if (config.origin() != null && !config.origin().isBlank()) {
            configuration.setOrigin(config.origin());
        }

        if (config.transportMode() == TransportMode.WSS) {
            PemTlsMaterialLoader.LoadedKeyStore loadedKeyStore = new PemTlsMaterialLoader().load(config.tls());
            configuration.setSSLProtocol(config.tls().protocol());
            configuration.setNeedClientAuth(false);
            configuration.setKeyStore(loadedKeyStore.inputStream());
            configuration.setKeyStoreFormat("PKCS12");
            configuration.setKeyStorePassword(loadedKeyStore.password());
        }

        return configuration;
    }
}
