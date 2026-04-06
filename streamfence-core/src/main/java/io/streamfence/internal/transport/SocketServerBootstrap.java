package io.streamfence.internal.transport;

import com.corundumstudio.socketio.AckMode;
import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.Transport;
import io.streamfence.internal.security.AuthRateLimiter;
import io.streamfence.internal.security.StaticTokenValidator;
import io.streamfence.TokenValidator;
import io.streamfence.internal.config.NamespaceConfig;
import io.streamfence.internal.config.ServerConfig;
import io.streamfence.internal.config.TopicPolicy;
import io.streamfence.TransportMode;
import io.streamfence.internal.delivery.ClientSessionRegistry;
import io.streamfence.internal.delivery.ClientSessionState;
import io.streamfence.internal.delivery.TopicDispatcher;
import io.streamfence.internal.observability.ManagementHttpServer;
import io.streamfence.ServerMetrics;
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

public final class SocketServerBootstrap implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SocketServerBootstrap.class);

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

    public SocketServerBootstrap(ServerConfig serverConfig) {
        this.serverConfig = serverConfig;
        this.metrics = new ServerMetrics();
        this.sessionRegistry = new ClientSessionRegistry();
        this.topicRegistry = new TopicRegistry(serverConfig);
        AckTracker ackTracker = new AckTracker();
        RetryService retryService = new RetryService(ackTracker);

        int senderThreads = serverConfig.senderThreads() > 0
                ? serverConfig.senderThreads()
                : Math.max(4, Runtime.getRuntime().availableProcessors());
        this.topicDispatcher = new TopicDispatcher(
                topicRegistry, sessionRegistry, ackTracker, retryService, metrics, senderThreads, new ObjectMapper());

        this.authRateLimiter = serverConfig.authMode().name().equals("TOKEN")
                ? new AuthRateLimiter(serverConfig.authRejectMaxPerWindow(), serverConfig.authRejectWindowMs())
                : null;
        TokenValidator tokenValidator = serverConfig.authMode().name().equals("TOKEN")
                ? new StaticTokenValidator(serverConfig.staticTokens())
                : null;

        this.socketIOServer = new SocketIOServer(toConfiguration(serverConfig));
        registerNamespaces(topicRegistry, sessionRegistry, tokenValidator);

        // Register pull-gauges for each topic after the session registry and
        // dispatcher exist. Gauges evaluate at scrape time.
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

    public void start() {
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
    }

    public void stop() {
        close();
    }

    public ServerMetrics metrics() {
        return metrics;
    }

    /**
     * Exposed for integration tests that need to publish directly through
     * the dispatcher without the client→server network hop.
     */
    public TopicDispatcher topicDispatcher() {
        return topicDispatcher;
    }

    public int managementPort() {
        return managementHttpServer == null ? -1 : managementHttpServer.boundPort();
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

    private void registerNamespaces(TopicRegistry topicRegistry, ClientSessionRegistry sessionRegistry, TokenValidator tokenValidator) {
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
                    authRateLimiter
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
            // NOTE: netty-socketio 2.0.14's Configuration API exposes only
            // setSSLProtocol. There is no cipher-suite allow-list setter, so
            // the JVM default cipher list applies. Documented as a known
            // limitation for this release.
            configuration.setSSLProtocol(config.tls().protocol());
            configuration.setNeedClientAuth(false);
            configuration.setKeyStore(loadedKeyStore.inputStream());
            configuration.setKeyStoreFormat("PKCS12");
            configuration.setKeyStorePassword(loadedKeyStore.password());
        }

        return configuration;
    }

    @Override
    public void close() {
        long deadline = System.currentTimeMillis() + Math.max(1, serverConfig.shutdownDrainMs());

        // 1. Stop scrape traffic first so the management endpoint drains
        //    before the game plane tears down.
        if (managementHttpServer != null) {
            try {
                managementHttpServer.stop(2);
            } catch (RuntimeException exception) {
                LOG.warn("event=shutdown_step_failed phase=management_http error={}", exception.getMessage());
            }
        }

        // 2. socketIOServer.stop() can block if a client refuses to close.
        //    Run it on a sacrificial thread so we can enforce an upper bound.
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

        // 3. Stop the scheduler (retry tick, rate-limiter reap, PEM watcher).
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

        // 4. Drain sender executor under its existing bounded timeout.
        topicDispatcher.close();
    }
}
