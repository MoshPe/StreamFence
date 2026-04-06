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

/**
 * The main entry point for the StreamFence Socket.IO server.
 *
 * <p>Create instances via the fluent builder returned by {@link #builder()}, or by passing a
 * fully-constructed {@link SocketIoServerSpec} to {@link #create(SocketIoServerSpec)}.
 * The server implements {@link AutoCloseable} and can be used in a try-with-resources block.
 *
 * <pre>{@code
 * try (SocketIoServer server = SocketIoServer.builder()
 *         .host("127.0.0.1")
 *         .port(9092)
 *         .namespace(NamespaceSpec.builder("/feed")
 *                 .topic("prices")
 *                 .build())
 *         .buildServer()) {
 *     server.start();
 *     server.publish("/feed", "prices", Map.of("value", 42));
 * }
 * }</pre>
 */
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

    /**
     * Returns a new fluent builder for configuring and constructing a {@link SocketIoServer}.
     *
     * @return a new {@link SocketIoServerBuilder}
     */
    public static SocketIoServerBuilder builder() {
        return new SocketIoServerBuilder();
    }

    /**
     * Creates a {@link SocketIoServer} from a fully-constructed spec. Equivalent to calling
     * {@code SocketIoServer.builder()} and using {@link SocketIoServerBuilder#build()} /
     * {@link SocketIoServerBuilder#buildServer()}.
     *
     * @param spec the complete server specification; must not be {@code null}
     * @return a configured, not-yet-started server
     */
    public static SocketIoServer create(SocketIoServerSpec spec) {
        return new SocketIoServer(spec);
    }

    /**
     * Returns the specification this server was built from.
     *
     * @return the {@link SocketIoServerSpec}
     */
    public SocketIoServerSpec spec() {
        return spec;
    }

    /**
     * Starts the Socket.IO server, binds the configured port, and begins accepting
     * connections. Also starts the management HTTP server (if configured) and schedules
     * background tasks.
     *
     * @throws IllegalStateException if the server is already running or the management
     *                               server fails to bind
     */
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

    /**
     * Stops the server. Equivalent to calling {@link #close()}.
     */
    public void stop() {
        close();
    }

    /**
     * Returns the metrics collector for this server.
     *
     * @return the {@link ServerMetrics} instance
     */
    public ServerMetrics metrics() {
        return metrics;
    }

    /**
     * Broadcasts {@code payload} to every subscriber of {@code topic} on {@code namespace}.
     * Honors the topic's delivery mode, queue limits, and overflow policy.
     *
     * @param namespace the namespace path (e.g. {@code "/feed"})
     * @param topic     the topic name
     * @param payload   any serialisable object; passed through as-is to netty-socketio
     * @throws IllegalArgumentException if the namespace or topic is unknown
     */
    public void publish(String namespace, String topic, Object payload) {
        topicDispatcher.publish(namespace, topic, payload);
    }

    /**
     * Sends {@code payload} to a single connected client that is subscribed to {@code topic}.
     * Silently no-ops if the client is not connected; logs a warning and returns if the client
     * is connected but not subscribed.
     *
     * @param namespace the namespace path
     * @param clientId  the target client's session ID
     * @param topic     the topic name
     * @param payload   any serialisable object; passed through as-is to netty-socketio
     */
    public void publishTo(String namespace, String clientId, String topic, Object payload) {
        topicDispatcher.publishTo(namespace, clientId, topic, payload);
    }

    /**
     * Returns the port the management HTTP server is listening on, or {@code -1} if it is
     * not configured.
     *
     * @return the bound management port, or {@code -1}
     */
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
