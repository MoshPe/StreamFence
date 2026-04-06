package io.streamfence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SocketIoServerSpecLoaderTest {

    private static final String MINIMAL_YAML = """
            host: 127.0.0.1
            port: 9090
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
                overflowAction: REJECT_NEW
                maxQueuedMessagesPerClient: 8
                maxQueuedBytesPerClient: 1024
                ackTimeoutMs: 1000
                maxRetries: 0
                coalesce: false
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
                authRequired: false
              - namespace: /bulk
                topic: uploads
                deliveryMode: AT_LEAST_ONCE
                overflowAction: REJECT_NEW
                maxQueuedMessagesPerClient: 4
                maxQueuedBytesPerClient: 65536
                ackTimeoutMs: 5000
                maxRetries: 2
                coalesce: false
                allowPolling: false
                authRequired: false
            """;

    private static final String MINIMAL_JSON = """
            {
              "host": "127.0.0.1",
              "port": 9090,
              "transportMode": "WS",
              "pingIntervalMs": 15000,
              "pingTimeoutMs": 30000,
              "maxFramePayloadLength": 6291456,
              "maxHttpContentLength": 6291456,
              "compressionEnabled": false,
              "enableCors": false,
              "authMode": "NONE",
              "namespaces": {
                "/non-reliable": { "authRequired": false },
                "/reliable": { "authRequired": false },
                "/bulk": { "authRequired": false }
              },
              "topicPolicies": [
                {
                  "namespace": "/non-reliable",
                  "topic": "prices",
                  "deliveryMode": "BEST_EFFORT",
                  "overflowAction": "REJECT_NEW",
                  "maxQueuedMessagesPerClient": 8,
                  "maxQueuedBytesPerClient": 1024,
                  "ackTimeoutMs": 1000,
                  "maxRetries": 0,
                  "coalesce": false,
                  "allowPolling": true,
                  "authRequired": false
                },
                {
                  "namespace": "/reliable",
                  "topic": "alerts",
                  "deliveryMode": "AT_LEAST_ONCE",
                  "overflowAction": "REJECT_NEW",
                  "maxQueuedMessagesPerClient": 16,
                  "maxQueuedBytesPerClient": 4096,
                  "ackTimeoutMs": 2000,
                  "maxRetries": 3,
                  "coalesce": false,
                  "allowPolling": true,
                  "authRequired": false
                },
                {
                  "namespace": "/bulk",
                  "topic": "uploads",
                  "deliveryMode": "AT_LEAST_ONCE",
                  "overflowAction": "REJECT_NEW",
                  "maxQueuedMessagesPerClient": 4,
                  "maxQueuedBytesPerClient": 65536,
                  "ackTimeoutMs": 5000,
                  "maxRetries": 2,
                  "coalesce": false,
                  "allowPolling": false,
                  "authRequired": false
                }
              ]
            }
            """;

    @Test
    void fromYamlLoadsValidDocument() throws IOException {
        Path dir = Files.createTempDirectory("spec-loader-");
        try {
            Path yaml = dir.resolve("application.yaml");
            Files.writeString(yaml, MINIMAL_YAML);

            SocketIoServerSpec spec = SocketIoServerSpec.fromYaml(yaml);

            assertThat(spec.host()).isEqualTo("127.0.0.1");
            assertThat(spec.port()).isEqualTo(9090);
            assertThat(spec.transportMode()).isEqualTo(TransportMode.WS);
            assertThat(spec.authMode()).isEqualTo(AuthMode.NONE);
            assertThat(spec.namespaces()).extracting(NamespaceSpec::path)
                    .contains("/non-reliable", "/reliable", "/bulk");
            NamespaceSpec nonReliable = spec.namespaces().stream()
                    .filter(ns -> ns.path().equals("/non-reliable"))
                    .findFirst().orElseThrow();
            assertThat(nonReliable.topics()).containsExactly("prices");
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void fromJsonLoadsValidDocument() throws IOException {
        Path dir = Files.createTempDirectory("spec-loader-");
        try {
            Path json = dir.resolve("application.json");
            Files.writeString(json, MINIMAL_JSON);

            SocketIoServerSpec spec = SocketIoServerSpec.fromJson(json);

            assertThat(spec.host()).isEqualTo("127.0.0.1");
            assertThat(spec.namespaces()).extracting(NamespaceSpec::path)
                    .contains("/non-reliable");
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void fromClasspathLoadsBundledResource() {
        SocketIoServerSpec spec = SocketIoServerSpec.fromClasspath("loader-test.yaml");
        assertThat(spec.host()).isEqualTo("127.0.0.1");
        assertThat(spec.port()).isEqualTo(9090);
        assertThat(spec.namespaces()).extracting(NamespaceSpec::path)
                .contains("/non-reliable", "/reliable", "/bulk");
    }

    @Test
    void fromClasspathRejectsMissingResource() {
        assertThatThrownBy(() -> SocketIoServerSpec.fromClasspath("does-not-exist.yaml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("classpath resource not found");
    }

    @Test
    void fromYamlWrapsMalformedYamlWithPathAndLineNumber() throws IOException {
        Path dir = Files.createTempDirectory("spec-loader-");
        try {
            Path yaml = dir.resolve("malformed.yaml");
            Files.writeString(yaml, """
                    host: 127.0.0.1
                    port: 9090
                    transportMode: [this is not a scalar
                    """);

            assertThatThrownBy(() -> SocketIoServerSpec.fromYaml(yaml))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(yaml.toString())
                    .hasMessageContaining("line");
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
