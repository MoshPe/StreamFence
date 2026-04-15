package io.streamfence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent builder for {@link SocketIoServer} and {@link SocketIoServerSpec}.
 *
 * <p>Obtain an instance via {@link SocketIoServer#builder()}. All setters return {@code this}
 * for chaining. Call {@link #buildServer()} to get a ready-to-start server, or {@link #build()}
 * to obtain just the validated spec.
 *
 * <p>Configuration can also be seeded from a YAML or JSON file via {@link #fromYaml(Path)},
 * {@link #fromJson(Path)}, or {@link #fromClasspath(String)}. Any fluent setter called
 * <em>after</em> the seed call overrides the loaded value; calls made <em>before</em> are
 * overwritten by the loaded values. Listeners and {@link TokenValidator} are never overwritten
 * by a seed call.
 */
public final class SocketIoServerBuilder {

    /** Constructs a builder with sensible defaults. */
    public SocketIoServerBuilder() {
    }

    private String host = "0.0.0.0";
    private int port = 9092;
    private TransportMode transportMode = TransportMode.WS;
    private TLSConfig tls;
    private int pingIntervalMs = 20_000;
    private int pingTimeoutMs = 40_000;
    private int maxFramePayloadLength = 5_242_880;
    private int maxHttpContentLength = 5_242_880;
    private boolean compressionEnabled = true;
    private boolean enableCors;
    private String origin;
    private AuthMode authMode = AuthMode.NONE;
    private final Map<String, String> staticTokens = new HashMap<>();
    private final List<NamespaceSpec> namespaces = new ArrayList<>();
    private String managementHost = "0.0.0.0";
    private int managementPort;
    private int shutdownDrainMs = 10_000;
    private int senderThreads;
    private int authRejectWindowMs = 60_000;
    private int authRejectMaxPerWindow = 20;
    private String spillRootPath = SocketIoServerSpec.DEFAULT_SPILL_ROOT_PATH;
    private TokenValidator tokenValidator;
    private final List<ServerEventListener> listeners = new ArrayList<>();

    /**
     * Sets the hostname or IP address the server binds to.
     *
     * @param host hostname or IP address (default {@code "0.0.0.0"})
     * @return this builder
     */
    public SocketIoServerBuilder host(String host) {
        this.host = host;
        return this;
    }

    /**
     * Sets the TCP port the Socket.IO server listens on.
     *
     * @param port the port number (default 9092)
     * @return this builder
     */
    public SocketIoServerBuilder port(int port) {
        this.port = port;
        return this;
    }

    /**
     * Sets the transport mode ({@link TransportMode#WS} or {@link TransportMode#WSS}).
     *
     * @param transportMode the transport mode (default {@link TransportMode#WS})
     * @return this builder
     */
    public SocketIoServerBuilder transportMode(TransportMode transportMode) {
        this.transportMode = transportMode;
        return this;
    }

    /**
     * Sets the TLS configuration. Required when {@code transportMode} is
     * {@link TransportMode#WSS}.
     *
     * @param tls the TLS settings; {@code null} when TLS is not used
     * @return this builder
     */
    public SocketIoServerBuilder tls(TLSConfig tls) {
        this.tls = tls;
        return this;
    }

    /**
     * Sets the WebSocket ping interval (in milliseconds).
     *
     * @param pingIntervalMs ping interval in milliseconds (default 20 000)
     * @return this builder
     */
    public SocketIoServerBuilder pingIntervalMs(int pingIntervalMs) {
        this.pingIntervalMs = pingIntervalMs;
        return this;
    }

    /**
     * Sets the WebSocket ping timeout (in milliseconds). If no pong is received within this
     * window after a ping, the connection is considered dead.
     *
     * @param pingTimeoutMs ping timeout in milliseconds (default 40 000)
     * @return this builder
     */
    public SocketIoServerBuilder pingTimeoutMs(int pingTimeoutMs) {
        this.pingTimeoutMs = pingTimeoutMs;
        return this;
    }

    /**
     * Sets the maximum WebSocket frame payload length in bytes.
     *
     * @param maxFramePayloadLength maximum bytes per frame (default 5 242 880)
     * @return this builder
     */
    public SocketIoServerBuilder maxFramePayloadLength(int maxFramePayloadLength) {
        this.maxFramePayloadLength = maxFramePayloadLength;
        return this;
    }

    /**
     * Sets the maximum HTTP content length in bytes (applies to the Socket.IO HTTP
     * long-polling transport).
     *
     * @param maxHttpContentLength maximum bytes per HTTP request body (default 5 242 880)
     * @return this builder
     */
    public SocketIoServerBuilder maxHttpContentLength(int maxHttpContentLength) {
        this.maxHttpContentLength = maxHttpContentLength;
        return this;
    }

    /**
     * Enables or disables HTTP and WebSocket compression.
     *
     * @param compressionEnabled {@code true} to enable compression (default {@code true})
     * @return this builder
     */
    public SocketIoServerBuilder compressionEnabled(boolean compressionEnabled) {
        this.compressionEnabled = compressionEnabled;
        return this;
    }

    /**
     * Enables or disables CORS headers on HTTP responses.
     *
     * @param enableCors {@code true} to set permissive CORS headers
     * @return this builder
     */
    public SocketIoServerBuilder enableCors(boolean enableCors) {
        this.enableCors = enableCors;
        return this;
    }

    /**
     * Sets the allowed CORS origin. Only used when {@link #enableCors(boolean)} is
     * {@code true}.
     *
     * @param origin the allowed origin (e.g. {@code "https://example.com"}), or {@code null}
     *               for no restriction
     * @return this builder
     */
    public SocketIoServerBuilder origin(String origin) {
        this.origin = origin;
        return this;
    }

    /**
     * Sets the server-level authentication mode.
     *
     * @param authMode {@link AuthMode#NONE} (default) or {@link AuthMode#TOKEN}
     * @return this builder
     */
    public SocketIoServerBuilder authMode(AuthMode authMode) {
        this.authMode = authMode;
        return this;
    }

    /**
     * Adds a single static token entry used by the built-in {@link TokenValidator} when no
     * custom validator is provided.
     *
     * @param principal the principal name associated with this token
     * @param token     the bearer token string
     * @return this builder
     */
    public SocketIoServerBuilder staticToken(String principal, String token) {
        this.staticTokens.put(principal, token);
        return this;
    }

    /**
     * Adds multiple static token entries at once. See {@link #staticToken(String, String)}.
     *
     * @param staticTokens a map of principal name to bearer token
     * @return this builder
     */
    public SocketIoServerBuilder staticTokens(Map<String, String> staticTokens) {
        this.staticTokens.putAll(staticTokens);
        return this;
    }

    /**
     * Adds a namespace to the server configuration.
     *
     * @param namespace the fully-validated {@link NamespaceSpec} to register
     * @return this builder
     */
    public SocketIoServerBuilder namespace(NamespaceSpec namespace) {
        this.namespaces.add(namespace);
        return this;
    }

    /**
     * Sets the bind address for the management HTTP server (Prometheus scrape endpoint).
     *
     * @param managementHost hostname or IP to bind to (default {@code "0.0.0.0"})
     * @return this builder
     */
    public SocketIoServerBuilder managementHost(String managementHost) {
        this.managementHost = managementHost;
        return this;
    }

    /**
     * Sets the port for the management HTTP server. When set to {@code 0} (the default),
     * no management server is started.
     *
     * @param managementPort the HTTP port; {@code 0} disables the management server
     * @return this builder
     */
    public SocketIoServerBuilder managementPort(int managementPort) {
        this.managementPort = managementPort;
        return this;
    }

    /**
     * Sets the grace period (in milliseconds) allowed for in-flight messages to drain during
     * server shutdown.
     *
     * @param shutdownDrainMs drain budget in milliseconds (default 10 000)
     * @return this builder
     */
    public SocketIoServerBuilder shutdownDrainMs(int shutdownDrainMs) {
        this.shutdownDrainMs = shutdownDrainMs;
        return this;
    }

    /**
     * Sets the number of threads in the sender thread pool. When {@code 0} (the default),
     * the pool size is derived from {@link Runtime#availableProcessors()}.
     *
     * @param senderThreads thread count; {@code 0} for auto-sizing
     * @return this builder
     */
    public SocketIoServerBuilder senderThreads(int senderThreads) {
        this.senderThreads = senderThreads;
        return this;
    }

    /**
     * Sets the sliding-window duration (in milliseconds) for the auth rate limiter.
     * Connections that fail authentication more than {@code authRejectMaxPerWindow} times
     * within this window are rate-limited.
     *
     * @param authRejectWindowMs window size in milliseconds (default 60 000)
     * @return this builder
     */
    public SocketIoServerBuilder authRejectWindowMs(int authRejectWindowMs) {
        this.authRejectWindowMs = authRejectWindowMs;
        return this;
    }

    /**
     * Sets the maximum number of authentication rejections permitted within the sliding
     * window before the source address is rate-limited.
     *
     * @param authRejectMaxPerWindow maximum failures per window (default 20)
     * @return this builder
     */
    public SocketIoServerBuilder authRejectMaxPerWindow(int authRejectMaxPerWindow) {
        this.authRejectMaxPerWindow = authRejectMaxPerWindow;
        return this;
    }

    /**
     * Sets the root directory used for spill-to-disk queue storage.
     *
     * @param spillRootPath the spill directory path; blank values fall back to the default
     *                      {@code ".streamfence-spill"}
     * @return this builder
     */
    public SocketIoServerBuilder spillRootPath(String spillRootPath) {
        this.spillRootPath = spillRootPath;
        return this;
    }

    /**
     * Sets a custom {@link TokenValidator} for {@link AuthMode#TOKEN} authentication.
     * When provided, this validator takes precedence over any static token map.
     *
     * @param tokenValidator the custom validator; {@code null} falls back to the static map
     * @return this builder
     */
    public SocketIoServerBuilder tokenValidator(TokenValidator tokenValidator) {
        this.tokenValidator = tokenValidator;
        return this;
    }

    /**
     * Registers a single {@link ServerEventListener} to receive runtime callbacks.
     * Multiple listeners can be registered; they are invoked in registration order.
     *
     * @param listener the listener to add; must not be {@code null}
     * @return this builder
     */
    public SocketIoServerBuilder listener(ServerEventListener listener) {
        this.listeners.add(listener);
        return this;
    }

    /**
     * Registers multiple {@link ServerEventListener}s at once.
     *
     * @param listeners the listeners to add; must not be {@code null}
     * @return this builder
     */
    public SocketIoServerBuilder listeners(List<ServerEventListener> listeners) {
        this.listeners.addAll(listeners);
        return this;
    }

    /**
     * Seeds the builder from a YAML configuration file. Every field loaded from the file is
     * copied into builder state and can still be overridden by subsequent fluent calls.
     * Listeners and {@link TokenValidator} are never overwritten by this call.
     *
     * @param path path to the YAML configuration file
     * @return this builder
     */
    public SocketIoServerBuilder fromYaml(Path path) {
        return seedFrom(SocketIoServerSpec.fromYaml(path));
    }

    /**
     * Seeds the builder from a JSON configuration file. See {@link #fromYaml(Path)} for
     * override semantics.
     *
     * @param path path to the JSON configuration file
     * @return this builder
     */
    public SocketIoServerBuilder fromJson(Path path) {
        return seedFrom(SocketIoServerSpec.fromJson(path));
    }

    /**
     * Seeds the builder from a classpath resource. The format ({@code .yaml} or {@code .json})
     * is inferred from the file extension. See {@link #fromYaml(Path)} for override semantics.
     *
     * @param resource classpath resource name (e.g. {@code "application.yaml"})
     * @return this builder
     */
    public SocketIoServerBuilder fromClasspath(String resource) {
        return seedFrom(SocketIoServerSpec.fromClasspath(resource));
    }

    private SocketIoServerBuilder seedFrom(SocketIoServerSpec spec) {
        this.host = spec.host();
        this.port = spec.port();
        this.transportMode = spec.transportMode();
        this.tls = spec.tls();
        this.pingIntervalMs = spec.pingIntervalMs();
        this.pingTimeoutMs = spec.pingTimeoutMs();
        this.maxFramePayloadLength = spec.maxFramePayloadLength();
        this.maxHttpContentLength = spec.maxHttpContentLength();
        this.compressionEnabled = spec.compressionEnabled();
        this.enableCors = spec.enableCors();
        this.origin = spec.origin();
        this.authMode = spec.authMode();
        this.staticTokens.clear();
        this.staticTokens.putAll(spec.staticTokens());
        this.namespaces.clear();
        this.namespaces.addAll(spec.namespaces());
        this.managementHost = spec.managementHost();
        this.managementPort = spec.managementPort();
        this.shutdownDrainMs = spec.shutdownDrainMs();
        this.senderThreads = spec.senderThreads();
        this.authRejectWindowMs = spec.authRejectWindowMs();
        this.authRejectMaxPerWindow = spec.authRejectMaxPerWindow();
        this.spillRootPath = spec.spillRootPath();
        // Intentionally do NOT overwrite listeners or tokenValidator — those
        // cannot be expressed in YAML/JSON and must be set programmatically.
        return this;
    }

    /**
     * Builds and validates the {@link SocketIoServerSpec} from the current builder state.
     *
     * @return the validated spec
     * @throws IllegalArgumentException if any field value is invalid
     */
    public SocketIoServerSpec build() {
        return new SocketIoServerSpec(
                host,
                port,
                transportMode,
                tls,
                pingIntervalMs,
                pingTimeoutMs,
                maxFramePayloadLength,
                maxHttpContentLength,
                compressionEnabled,
                enableCors,
                origin,
                authMode,
                staticTokens,
                namespaces,
                managementHost,
                managementPort,
                shutdownDrainMs,
                senderThreads,
                authRejectWindowMs,
                authRejectMaxPerWindow,
                spillRootPath,
                tokenValidator,
                listeners
        );
    }

    /**
     * Builds the spec and immediately wraps it in a {@link SocketIoServer}. The server is
     * not yet started; call {@link SocketIoServer#start()} to begin accepting connections.
     *
     * @return a configured, not-yet-started {@link SocketIoServer}
     * @throws IllegalArgumentException if any field value is invalid
     */
    public SocketIoServer buildServer() {
        return SocketIoServer.create(build());
    }
}
