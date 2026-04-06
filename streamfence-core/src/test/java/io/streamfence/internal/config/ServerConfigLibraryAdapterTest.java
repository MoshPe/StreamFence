package io.streamfence.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.streamfence.SocketIoServerSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServerConfigLibraryAdapterTest {

    @Test
    void loadSpecAdaptsYamlIntoLibrarySpec() throws Exception {
        Path configPath = Files.createTempFile("wsserver-spec", ".yaml");
        try {
            Files.writeString(configPath, """
                    host: 127.0.0.1
                    port: 9092
                    transportMode: WS
                    pingIntervalMs: 20000
                    pingTimeoutMs: 40000
                    maxFramePayloadLength: 5242880
                    maxHttpContentLength: 5242880
                    compressionEnabled: true
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
                        topics: [prices, quotes]
                        deliveryMode: BEST_EFFORT
                        overflowAction: REJECT_NEW
                        maxQueuedMessagesPerClient: 64
                        maxQueuedBytesPerClient: 524288
                        ackTimeoutMs: 1000
                        maxRetries: 0
                        coalesce: false
                        allowPolling: true
                        authRequired: false
                      - namespace: /reliable
                        topic: alerts
                        deliveryMode: BEST_EFFORT
                        overflowAction: REJECT_NEW
                        maxQueuedMessagesPerClient: 64
                        maxQueuedBytesPerClient: 524288
                        ackTimeoutMs: 1000
                        maxRetries: 0
                        coalesce: false
                        allowPolling: true
                        authRequired: false
                      - namespace: /bulk
                        topic: blob
                        deliveryMode: BEST_EFFORT
                        overflowAction: REJECT_NEW
                        maxQueuedMessagesPerClient: 2
                        maxQueuedBytesPerClient: 10485760
                        ackTimeoutMs: 1000
                        maxRetries: 0
                        coalesce: false
                        allowPolling: false
                        authRequired: false
                    """);

            SocketIoServerSpec spec = ServerConfigLoader.loadSpec(configPath);

            assertThat(spec.host()).isEqualTo("127.0.0.1");
            assertThat(spec.namespaces()).extracting(namespace -> namespace.path())
                    .containsExactlyInAnyOrder("/non-reliable", "/reliable", "/bulk");
            assertThat(spec.namespaces().stream().filter(namespace -> namespace.path().equals("/non-reliable")).findFirst().orElseThrow().topics())
                    .isEqualTo(List.of("prices", "quotes"));
            assertThat(spec.namespaces().stream().filter(namespace -> namespace.path().equals("/reliable")).findFirst().orElseThrow().topics())
                    .isEqualTo(List.of("alerts"));
            assertThat(spec.namespaces().stream().filter(namespace -> namespace.path().equals("/bulk")).findFirst().orElseThrow().topics())
                    .isEqualTo(List.of("blob"));
        } finally {
            Files.deleteIfExists(configPath);
        }
    }

    @Test
    void loadSpecRejectsMixedTopicPoliciesWithinNamespace() throws Exception {
        Path configPath = Files.createTempFile("wsserver-mixed-spec", ".yaml");
        try {
            Files.writeString(configPath, """
                    host: 127.0.0.1
                    port: 9092
                    transportMode: WS
                    pingIntervalMs: 20000
                    pingTimeoutMs: 40000
                    maxFramePayloadLength: 5242880
                    maxHttpContentLength: 5242880
                    compressionEnabled: true
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
                        overflowAction: REJECT_NEW
                        maxQueuedMessagesPerClient: 64
                        maxQueuedBytesPerClient: 524288
                        ackTimeoutMs: 1000
                        maxRetries: 0
                        coalesce: false
                        allowPolling: true
                        authRequired: false
                      - namespace: /non-reliable
                        topic: quotes
                        deliveryMode: BEST_EFFORT
                        overflowAction: COALESCE
                        maxQueuedMessagesPerClient: 64
                        maxQueuedBytesPerClient: 524288
                        ackTimeoutMs: 1000
                        maxRetries: 0
                        coalesce: true
                        allowPolling: true
                        authRequired: false
                      - namespace: /reliable
                        topic: alerts
                        deliveryMode: BEST_EFFORT
                        overflowAction: REJECT_NEW
                        maxQueuedMessagesPerClient: 64
                        maxQueuedBytesPerClient: 524288
                        ackTimeoutMs: 1000
                        maxRetries: 0
                        coalesce: false
                        allowPolling: true
                        authRequired: false
                      - namespace: /bulk
                        topic: blob
                        deliveryMode: BEST_EFFORT
                        overflowAction: REJECT_NEW
                        maxQueuedMessagesPerClient: 2
                        maxQueuedBytesPerClient: 10485760
                        ackTimeoutMs: 1000
                        maxRetries: 0
                        coalesce: false
                        allowPolling: false
                        authRequired: false
                    """);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ServerConfigLoader.loadSpec(configPath))
                    .withMessageContaining("/non-reliable");
        } finally {
            Files.deleteIfExists(configPath);
        }
    }
}
