package io.streamfence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SocketIoServerBuilder {

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
    private TokenValidator tokenValidator;
    private final List<ServerEventListener> listeners = new ArrayList<>();

    public SocketIoServerBuilder host(String host) {
        this.host = host;
        return this;
    }

    public SocketIoServerBuilder port(int port) {
        this.port = port;
        return this;
    }

    public SocketIoServerBuilder transportMode(TransportMode transportMode) {
        this.transportMode = transportMode;
        return this;
    }

    public SocketIoServerBuilder tls(TLSConfig tls) {
        this.tls = tls;
        return this;
    }

    public SocketIoServerBuilder pingIntervalMs(int pingIntervalMs) {
        this.pingIntervalMs = pingIntervalMs;
        return this;
    }

    public SocketIoServerBuilder pingTimeoutMs(int pingTimeoutMs) {
        this.pingTimeoutMs = pingTimeoutMs;
        return this;
    }

    public SocketIoServerBuilder maxFramePayloadLength(int maxFramePayloadLength) {
        this.maxFramePayloadLength = maxFramePayloadLength;
        return this;
    }

    public SocketIoServerBuilder maxHttpContentLength(int maxHttpContentLength) {
        this.maxHttpContentLength = maxHttpContentLength;
        return this;
    }

    public SocketIoServerBuilder compressionEnabled(boolean compressionEnabled) {
        this.compressionEnabled = compressionEnabled;
        return this;
    }

    public SocketIoServerBuilder enableCors(boolean enableCors) {
        this.enableCors = enableCors;
        return this;
    }

    public SocketIoServerBuilder origin(String origin) {
        this.origin = origin;
        return this;
    }

    public SocketIoServerBuilder authMode(AuthMode authMode) {
        this.authMode = authMode;
        return this;
    }

    public SocketIoServerBuilder staticToken(String principal, String token) {
        this.staticTokens.put(principal, token);
        return this;
    }

    public SocketIoServerBuilder staticTokens(Map<String, String> staticTokens) {
        this.staticTokens.putAll(staticTokens);
        return this;
    }

    public SocketIoServerBuilder namespace(NamespaceSpec namespace) {
        this.namespaces.add(namespace);
        return this;
    }

    public SocketIoServerBuilder managementHost(String managementHost) {
        this.managementHost = managementHost;
        return this;
    }

    public SocketIoServerBuilder managementPort(int managementPort) {
        this.managementPort = managementPort;
        return this;
    }

    public SocketIoServerBuilder shutdownDrainMs(int shutdownDrainMs) {
        this.shutdownDrainMs = shutdownDrainMs;
        return this;
    }

    public SocketIoServerBuilder senderThreads(int senderThreads) {
        this.senderThreads = senderThreads;
        return this;
    }

    public SocketIoServerBuilder authRejectWindowMs(int authRejectWindowMs) {
        this.authRejectWindowMs = authRejectWindowMs;
        return this;
    }

    public SocketIoServerBuilder authRejectMaxPerWindow(int authRejectMaxPerWindow) {
        this.authRejectMaxPerWindow = authRejectMaxPerWindow;
        return this;
    }

    public SocketIoServerBuilder tokenValidator(TokenValidator tokenValidator) {
        this.tokenValidator = tokenValidator;
        return this;
    }

    public SocketIoServerBuilder listener(ServerEventListener listener) {
        this.listeners.add(listener);
        return this;
    }

    public SocketIoServerBuilder listeners(List<ServerEventListener> listeners) {
        this.listeners.addAll(listeners);
        return this;
    }

    /**
     * Seeds the builder from a YAML configuration file. Every field loaded
     * from the file is copied into builder state and can still be overridden
     * by subsequent fluent calls. Listeners and {@link TokenValidator} added
     * after this call are retained.
     */
    public SocketIoServerBuilder fromYaml(Path path) {
        return seedFrom(SocketIoServerSpec.fromYaml(path));
    }

    /**
     * Seeds the builder from a JSON configuration file. See
     * {@link #fromYaml(Path)} for override semantics.
     */
    public SocketIoServerBuilder fromJson(Path path) {
        return seedFrom(SocketIoServerSpec.fromJson(path));
    }

    /**
     * Seeds the builder from a classpath resource. Format is inferred from
     * the extension.
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
        // Intentionally do NOT overwrite listeners or tokenValidator — those
        // cannot be expressed in YAML/JSON and must be set programmatically.
        return this;
    }

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
                tokenValidator,
                listeners
        );
    }

    public SocketIoServer buildServer() {
        return SocketIoServer.create(build());
    }
}
