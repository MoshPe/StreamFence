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
     * Loads a {@code SocketIoServerSpec} from a YAML file on the filesystem.
     *
     * @throws IllegalArgumentException if {@code path} is null, unreadable, or
     *         the document fails validation. The cause and message include the
     *         source path and — when Jackson provides it — the offending line.
     */
    public static SocketIoServerSpec fromYaml(Path path) {
        return loadFromPath(path);
    }

    /**
     * Loads a {@code SocketIoServerSpec} from a JSON file on the filesystem.
     *
     * @throws IllegalArgumentException on any read or validation failure. See
     *         {@link #fromYaml(Path)} for error details.
     */
    public static SocketIoServerSpec fromJson(Path path) {
        return loadFromPath(path);
    }

    /**
     * Loads a {@code SocketIoServerSpec} from a classpath resource. The
     * format is inferred from the resource name: {@code .json} → JSON,
     * anything else → YAML.
     *
     * @throws IllegalArgumentException if the resource is not on the classpath
     *         or cannot be parsed.
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
