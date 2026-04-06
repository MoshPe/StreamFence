package io.streamfence;

import io.streamfence.internal.config.ServerConfigLoader;
import io.streamfence.internal.config.SocketIoServerSpecMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot of all configuration fields for a {@link SocketIoServer}.
 *
 * <p>Create instances via {@link SocketIoServer#builder()}, or load them directly from a
 * YAML or JSON file using the static factory methods {@link #fromYaml(Path)},
 * {@link #fromJson(Path)}, and {@link #fromClasspath(String)}.
 *
 * @param host                  the bind address
 * @param port                  the Socket.IO listen port
 * @param transportMode         the transport mode ({@link TransportMode#WS} or
 *                              {@link TransportMode#WSS})
 * @param tls                   TLS settings; required when {@code transportMode} is
 *                              {@link TransportMode#WSS}
 * @param pingIntervalMs        WebSocket ping interval in milliseconds
 * @param pingTimeoutMs         WebSocket ping timeout in milliseconds
 * @param maxFramePayloadLength maximum WebSocket frame payload length in bytes
 * @param maxHttpContentLength  maximum HTTP request body length in bytes (polling transport)
 * @param compressionEnabled    whether HTTP and WebSocket compression is enabled
 * @param enableCors            whether permissive CORS headers are added to HTTP responses
 * @param origin                allowed CORS origin, or {@code null} for no restriction
 * @param authMode              server-level authentication mode
 * @param staticTokens          immutable map of principal to bearer token for built-in auth
 * @param namespaces            the namespace configurations; at least one is required
 * @param managementHost        bind address for the management HTTP server
 * @param managementPort        port for the Prometheus scrape endpoint; {@code 0} disables it
 * @param shutdownDrainMs       grace period in milliseconds during shutdown
 * @param senderThreads         sender thread pool size; {@code 0} means auto-size
 * @param authRejectWindowMs    sliding window duration for auth rate limiting in milliseconds
 * @param authRejectMaxPerWindow maximum auth rejections per window before rate limiting
 * @param tokenValidator        custom token validator; may be {@code null}
 * @param listeners             ordered list of event listeners
 */
public record SocketIoServerSpec(
        String host,
        int port,
        TransportMode transportMode,
        TLSConfig tls,
        int pingIntervalMs,
        int pingTimeoutMs,
        int maxFramePayloadLength,
        int maxHttpContentLength,
        boolean compressionEnabled,
        boolean enableCors,
        String origin,
        AuthMode authMode,
        Map<String, String> staticTokens,
        List<NamespaceSpec> namespaces,
        String managementHost,
        int managementPort,
        int shutdownDrainMs,
        int senderThreads,
        int authRejectWindowMs,
        int authRejectMaxPerWindow,
        TokenValidator tokenValidator,
        List<ServerEventListener> listeners
) {

    /**
     * Compact constructor that validates and normalises field values.
     *
     * @throws IllegalArgumentException if any required field is missing, out of range, or
     *                                  the namespace list is empty
     */
    public SocketIoServerSpec {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host is required");
        }
        if (port <= 0) {
            throw new IllegalArgumentException("port must be positive");
        }
        if (transportMode == null) {
            throw new IllegalArgumentException("transportMode is required");
        }
        if (pingIntervalMs <= 0) {
            throw new IllegalArgumentException("pingIntervalMs must be positive");
        }
        if (pingTimeoutMs <= 0) {
            throw new IllegalArgumentException("pingTimeoutMs must be positive");
        }
        if (maxFramePayloadLength <= 0) {
            throw new IllegalArgumentException("maxFramePayloadLength must be positive");
        }
        if (maxHttpContentLength <= 0) {
            throw new IllegalArgumentException("maxHttpContentLength must be positive");
        }
        if (authMode == null) {
            throw new IllegalArgumentException("authMode is required");
        }
        namespaces = List.copyOf(namespaces == null ? List.of() : namespaces);
        if (namespaces.isEmpty()) {
            throw new IllegalArgumentException("at least one namespace is required");
        }
        staticTokens = Map.copyOf(staticTokens == null ? Map.of() : staticTokens);
        listeners = List.copyOf(listeners == null ? List.of() : listeners);
        if (managementHost == null || managementHost.isBlank()) {
            managementHost = "0.0.0.0";
        }
        if (shutdownDrainMs <= 0) {
            shutdownDrainMs = 10_000;
        }
        if (senderThreads < 0) {
            throw new IllegalArgumentException("senderThreads must be zero or positive");
        }
        if (authRejectWindowMs <= 0) {
            authRejectWindowMs = 60_000;
        }
        if (authRejectMaxPerWindow <= 0) {
            authRejectMaxPerWindow = 20;
        }
    }

    /**
     * Loads a {@link SocketIoServerSpec} from a YAML file on the filesystem.
     *
     * @param path the path to the YAML configuration file
     * @return the loaded and validated spec
     * @throws IllegalArgumentException if {@code path} is null, unreadable, or the document
     *         fails validation; the message includes the source path and — when Jackson
     *         provides it — the offending line number
     */
    public static SocketIoServerSpec fromYaml(Path path) {
        return loadFromPath(path);
    }

    /**
     * Loads a {@link SocketIoServerSpec} from a JSON file on the filesystem.
     *
     * @param path the path to the JSON configuration file
     * @return the loaded and validated spec
     * @throws IllegalArgumentException on any read or validation failure; see
     *         {@link #fromYaml(Path)} for error details
     */
    public static SocketIoServerSpec fromJson(Path path) {
        return loadFromPath(path);
    }

    /**
     * Loads a {@link SocketIoServerSpec} from a classpath resource. The format is inferred
     * from the resource name: {@code .json} implies JSON; anything else is treated as YAML.
     *
     * @param resource the classpath resource name (e.g. {@code "application.yaml"})
     * @return the loaded and validated spec
     * @throws IllegalArgumentException if the resource is not found on the classpath or
     *         cannot be parsed
     */
    public static SocketIoServerSpec fromClasspath(String resource) {
        Objects.requireNonNull(resource, "resource");
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = SocketIoServerSpec.class.getClassLoader();
        }
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException(
                        "classpath resource not found: " + resource);
            }
            String suffix = resource.toLowerCase().endsWith(".json") ? ".json" : ".yaml";
            Path temp = Files.createTempFile("wsserver-config-", suffix);
            try {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                return SocketIoServerSpecMapper.fromServerConfig(ServerConfigLoader.load(temp));
            } finally {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw wrapLoadFailure(resource, exception);
        }
    }

    private static SocketIoServerSpec loadFromPath(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            return SocketIoServerSpecMapper.fromServerConfig(ServerConfigLoader.load(path));
        } catch (IOException exception) {
            throw wrapLoadFailure(path.toString(), exception);
        }
    }

    private static IllegalArgumentException wrapLoadFailure(String source, IOException exception) {
        String location = "";
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof com.fasterxml.jackson.core.JsonProcessingException jpe && jpe.getLocation() != null) {
                location = " at line " + jpe.getLocation().getLineNr();
                break;
            }
            cause = cause.getCause();
        }
        return new IllegalArgumentException(
                "Failed to load SocketIoServerSpec from " + source + location + ": " + exception.getMessage(),
                exception);
    }
}
