package io.streamfence.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.streamfence.DeliveryMode;
import io.streamfence.TransportMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ServerConfigLoaderTest {

    @Test
    void loadsValidYamlConfiguration() throws IOException {
        Path tempDir = Files.createTempDirectory("wsserver-config-");
        try {
            Path certPath = tempDir.resolve("server-cert.pem");
            Path keyPath = tempDir.resolve("server-key.pem");
            Files.writeString(certPath, "cert");
            Files.writeString(keyPath, "key");
            Path configPath = writeConfig(tempDir, """
                    host: 127.0.0.1
                    port: 8080
                    transportMode: WSS
                    tls:
                      certChainPemPath: %s
                      privateKeyPemPath: %s
                      keyStorePassword: changeit
                    pingIntervalMs: 15000
                    pingTimeoutMs: 30000
                    maxFramePayloadLength: 6291456
                    maxHttpContentLength: 6291456
                    compressionEnabled: true
                    enableCors: true
                    origin: https://app.example.com
                    authMode: TOKEN
                    staticTokens:
                      demo-client: secret
                    namespaces:
                      /non-reliable:
                        authRequired: false
                      /reliable:
                        authRequired: true
                      /bulk:
                        authRequired: true
                    topicPolicies:
                      - namespace: /non-reliable
                        topic: prices
                        deliveryMode: BEST_EFFORT
                        overflowAction: COALESCE
                        maxQueuedMessagesPerClient: 8
                        maxQueuedBytesPerClient: 1024
                        ackTimeoutMs: 1000
                        maxRetries: 0
                        coalesce: true
                        allowPolling: true
                        authRequired: false
                      - namespace: /reliable
                        topic: alerts
                        deliveryMode: AT_LEAST_ONCE
                        overflowAction: REJECT_NEW
                        maxQueuedMessagesPerClient: 16
                        maxQueuedBytesPerClient: 4096
                        ackTimeoutMs: 2000
                        maxRetries: 3
                        coalesce: false
                        allowPolling: true
                        authRequired: true
                    """.formatted(certPath.toString().replace("\\", "/"), keyPath.toString().replace("\\", "/")));

            ServerConfig config = ServerConfigLoader.load(configPath);

            assertThat(config.host()).isEqualTo("127.0.0.1");
            assertThat(config.transportMode()).isEqualTo(TransportMode.WSS);
            assertThat(config.tls().certChainPemPath()).isEqualTo(certPath.toString().replace("\\", "/"));
            assertThat(config.enableCors()).isTrue();
            assertThat(config.origin()).isEqualTo("https://app.example.com");
            assertThat(config.namespaces()).containsKeys("/non-reliable", "/reliable", "/bulk");
            assertThat(config.topicPolicies()).hasSize(2);
            assertThat(config.topicPolicies().getFirst().deliveryMode()).isEqualTo(DeliveryMode.BEST_EFFORT);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void expandsMultiTopicPolicyDefinitionIntoOnePolicyPerTopic() throws IOException {
        Path tempDir = Files.createTempDirectory("wsserver-config-");
        try {
            Path configPath = writeConfig(tempDir, """
                    host: 127.0.0.1
                    port: 8080
                    transportMode: WS
                    pingIntervalMs: 15000
                    pingTimeoutMs: 30000
                    maxFramePayloadLength: 6291456
                    maxHttpContentLength: 6291456
                    compressionEnabled: false
                    enableCors: false
                    authMode: NONE
                    namespaces:
                      /non-reliable:
                        authRequired: false
                      /reliable:
                        authRequired: false
                      /bulk:
                        authRequired: false
                    topicPolicies:
                      - namespace: /non-reliable
                        topics: [prices, quotes, spreads]
                        deliveryMode: BEST_EFFORT
                        overflowAction: COALESCE
                        maxQueuedMessagesPerClient: 8
                        maxQueuedBytesPerClient: 1024
                        ackTimeoutMs: 1000
                        maxRetries: 0
                        coalesce: true
                        allowPolling: true
                        authRequired: false
                    """);

            ServerConfig config = ServerConfigLoader.load(configPath);

            assertThat(config.topicPolicies()).hasSize(3);
            assertThat(config.topicPolicies())
                    .extracting(TopicPolicy::topic)
                    .containsExactly("prices", "quotes", "spreads");
            assertThat(config.topicPolicies())
                    .allMatch(p -> p.namespace().equals("/non-reliable"))
                    .allMatch(p -> p.deliveryMode() == DeliveryMode.BEST_EFFORT)
                    .allMatch(TopicPolicy::coalesce);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void rejectsTopicPolicyWithNeitherTopicNorTopics() throws IOException {
        Path tempDir = Files.createTempDirectory("wsserver-config-");
        try {
            Path configPath = writeConfig(tempDir, """
                    host: 127.0.0.1
                    port: 8080
                    transportMode: WS
                    pingIntervalMs: 15000
                    pingTimeoutMs: 30000
                    maxFramePayloadLength: 6291456
                    maxHttpContentLength: 6291456
                    compressionEnabled: false
                    enableCors: false
                    authMode: NONE
                    namespaces:
                      /non-reliable:
                        authRequired: false
                      /reliable:
                        authRequired: false
                      /bulk:
                        authRequired: false
                    topicPolicies:
                      - namespace: /non-reliable
                        deliveryMode: BEST_EFFORT
                        overflowAction: COALESCE
                        maxQueuedMessagesPerClient: 8
                        maxQueuedBytesPerClient: 1024
                        ackTimeoutMs: 1000
                        maxRetries: 0
                        coalesce: true
                        allowPolling: true
                        authRequired: false
                    """);

            assertThatThrownBy(() -> ServerConfigLoader.load(configPath))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'topic' or 'topics'");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void rejectsDuplicateTopicsWithinNamespace() throws IOException {
        Path tempDir = Files.createTempDirectory("wsserver-config-");
        try {
            Path configPath = writeConfig(tempDir, """
                    host: 127.0.0.1
                    port: 8080
                    transportMode: WS
                    pingIntervalMs: 15000
                    pingTimeoutMs: 30000
                    maxFramePayloadLength: 6291456
                    maxHttpContentLength: 6291456
                    compressionEnabled: false
                    enableCors: false
                    authMode: NONE
                    namespaces:
                      /non-reliable:
                        authRequired: false
                      /reliable:
                        authRequired: false
                      /bulk:
                        authRequired: false
                    topicPolicies:
                      - namespace: /non-reliable
                        topic: prices
                        deliveryMode: BEST_EFFORT
                        overflowAction: COALESCE
                        maxQueuedMessagesPerClient: 8
                        maxQueuedBytesPerClient: 1024
                        ackTimeoutMs: 1000
                        maxRetries: 0
                        coalesce: true
                        allowPolling: true
                        authRequired: false
                      - namespace: /non-reliable
                        topic: prices
                        deliveryMode: BEST_EFFORT
                        overflowAction: REJECT_NEW
                        maxQueuedMessagesPerClient: 8
                        maxQueuedBytesPerClient: 1024
                        ackTimeoutMs: 1000
                        maxRetries: 0
                        coalesce: false
                        allowPolling: true
                        authRequired: false
                    """);

            assertThatThrownBy(() -> ServerConfigLoader.load(configPath))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Duplicate topic policy");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void rejectsPayloadLimitsBelowRequiredCeiling() throws IOException {
        Path tempDir = Files.createTempDirectory("wsserver-config-");
        try {
            Path configPath = writeConfig(tempDir, """
                    host: 127.0.0.1
                    port: 8080
                    transportMode: WS
                    pingIntervalMs: 15000
                    pingTimeoutMs: 30000
                    maxFramePayloadLength: 1024
                    maxHttpContentLength: 1024
                    compressionEnabled: false
                    enableCors: false
                    authMode: NONE
                    namespaces:
                      /non-reliable:
                        authRequired: false
                      /reliable:
                        authRequired: false
                      /bulk:
                        authRequired: false
                    topicPolicies:
                      - namespace: /non-reliable
                        topic: prices
                        deliveryMode: BEST_EFFORT
                        overflowAction: COALESCE
                        maxQueuedMessagesPerClient: 8
                        maxQueuedBytesPerClient: 1024
                        ackTimeoutMs: 1000
                        maxRetries: 0
                        coalesce: true
                        allowPolling: true
                        authRequired: false
                    """);

            assertThatThrownBy(() -> ServerConfigLoader.load(configPath))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("4.5 MB");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void rejectsWssWithoutPemMaterial() throws IOException {
        Path tempDir = Files.createTempDirectory("wsserver-config-");
        try {
            Path configPath = writeConfig(tempDir, """
                    host: 127.0.0.1
                    port: 8080
                    transportMode: WSS
                    pingIntervalMs: 15000
                    pingTimeoutMs: 30000
                    maxFramePayloadLength: 6291456
                    maxHttpContentLength: 6291456
                    compressionEnabled: true
                    enableCors: false
                    authMode: NONE
                    namespaces:
                      /non-reliable:
                        authRequired: false
                      /reliable:
                        authRequired: false
                      /bulk:
                        authRequired: false
                    topicPolicies:
                      - namespace: /non-reliable
                        topic: prices
                        deliveryMode: BEST_EFFORT
                        overflowAction: COALESCE
                        maxQueuedMessagesPerClient: 8
                        maxQueuedBytesPerClient: 1024
                        ackTimeoutMs: 1000
                        maxRetries: 0
                        coalesce: true
                        allowPolling: true
                        authRequired: false
                    """);

            assertThatThrownBy(() -> ServerConfigLoader.load(configPath))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PEM");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void rejectsCorsEnabledWithoutOrigin() throws IOException {
        Path tempDir = Files.createTempDirectory("wsserver-config-");
        try {
            Path configPath = writeConfig(tempDir, """
                    host: 127.0.0.1
                    port: 8080
                    transportMode: WS
                    pingIntervalMs: 15000
                    pingTimeoutMs: 30000
                    maxFramePayloadLength: 6291456
                    maxHttpContentLength: 6291456
                    compressionEnabled: true
                    enableCors: true
                    authMode: NONE
                    namespaces:
                      /non-reliable:
                        authRequired: false
                      /reliable:
                        authRequired: false
                      /bulk:
                        authRequired: false
                    topicPolicies:
                      - namespace: /non-reliable
                        topic: prices
                        deliveryMode: BEST_EFFORT
                        overflowAction: COALESCE
                        maxQueuedMessagesPerClient: 8
                        maxQueuedBytesPerClient: 1024
                        ackTimeoutMs: 1000
                        maxRetries: 0
                        coalesce: true
                        allowPolling: true
                        authRequired: false
                    """);

            assertThatThrownBy(() -> ServerConfigLoader.load(configPath))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("origin");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static Path writeConfig(Path tempDir, String content) throws IOException {
        Path path = tempDir.resolve("application.yaml");
        Files.writeString(path, content);
        return path;
    }

    private static void deleteRecursively(Path root) {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }
}
