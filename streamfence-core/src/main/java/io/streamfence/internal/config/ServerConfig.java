package io.streamfence.internal.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.streamfence.AuthMode;
import io.streamfence.TLSConfig;
import io.streamfence.TransportMode;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ServerConfig(
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
        Map<String, NamespaceConfig> namespaces,
        List<TopicPolicy> topicPolicies,
        String managementHost,
        int managementPort,
        int shutdownDrainMs,
        int senderThreads,
        int authRejectWindowMs,
        int authRejectMaxPerWindow
) {
    public ServerConfig {
        if (managementHost == null || managementHost.isBlank()) {
            managementHost = "0.0.0.0";
        }
        if (shutdownDrainMs <= 0) {
            shutdownDrainMs = 10000;
        }
        if (authRejectWindowMs <= 0) {
            authRejectWindowMs = 60000;
        }
        if (authRejectMaxPerWindow <= 0) {
            authRejectMaxPerWindow = 20;
        }
    }

    /**
     * Legacy 15-arg constructor used by tests that predate the operational
     * fields. New callers should use the canonical constructor directly.
     */
    public ServerConfig(
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
            Map<String, NamespaceConfig> namespaces,
            List<TopicPolicy> topicPolicies) {
        this(host, port, transportMode, tls, pingIntervalMs, pingTimeoutMs,
                maxFramePayloadLength, maxHttpContentLength, compressionEnabled,
                enableCors, origin, authMode, staticTokens, namespaces, topicPolicies,
                "0.0.0.0", 0, 10000, 0, 60000, 20);
    }
}
