package io.streamfence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SocketIoServerBuilderFromYamlTest {

    private static final String BASE_YAML = """
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

    @Test
    void fromYamlSeedsTargetedFieldsAndSpillRootPath() throws IOException {
        SocketIoServerSpec spec = SocketIoServer.builder()
                .fromYaml(spillConfigPath())
                .build();

        assertThat(spec.host()).isEqualTo("127.0.0.1");
        assertThat(spec.port()).isEqualTo(9090);
        assertThat(spec.transportMode()).isEqualTo(TransportMode.WS);
        assertThat(spec.authMode()).isEqualTo(AuthMode.NONE);
        assertThat(spec.namespaces()).extracting(NamespaceSpec::path)
                .contains("/non-reliable", "/reliable", "/bulk");
        assertThat(spec.spillRootPath()).isEqualTo(".streamfence-spill");
    }

    @Test
    void fluentCallsAfterFromYamlOverrideLoadedValues() throws IOException {
        Path dir = Files.createTempDirectory("builder-seed-");
        try {
            Path yaml = dir.resolve("application.yaml");
            Files.writeString(yaml, BASE_YAML);

            SocketIoServerSpec spec = SocketIoServer.builder()
                    .fromYaml(yaml)
                    .host("192.168.1.5")
                    .port(12345)
                    .compressionEnabled(true)
                    .build();

            assertThat(spec.host()).isEqualTo("192.168.1.5");
            assertThat(spec.port()).isEqualTo(12345);
            assertThat(spec.compressionEnabled()).isTrue();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void fluentCallsBeforeFromYamlAreOverwrittenByLoadedValues() throws IOException {
        Path dir = Files.createTempDirectory("builder-seed-");
        try {
            Path yaml = dir.resolve("application.yaml");
            Files.writeString(yaml, BASE_YAML);

            SocketIoServerSpec spec = SocketIoServer.builder()
                    .host("0.0.0.0")
                    .port(1)
                    .fromYaml(yaml)
                    .build();

            assertThat(spec.host()).isEqualTo("127.0.0.1");
            assertThat(spec.port()).isEqualTo(9090);
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void listenersAndTokenValidatorAddedAfterFromYamlAreRetained() throws IOException {
        Path dir = Files.createTempDirectory("builder-seed-");
        try {
            Path yaml = dir.resolve("application.yaml");
            Files.writeString(yaml, BASE_YAML);

            ServerEventListener listener = new ServerEventListener() {};
            TokenValidator validator = (token, namespace, topic) -> AuthDecision.accept("test");

            SocketIoServerSpec spec = SocketIoServer.builder()
                    .fromYaml(yaml)
                    .listener(listener)
                    .tokenValidator(validator)
                    .build();

            assertThat(spec.listeners()).containsExactly(listener);
            assertThat(spec.tokenValidator()).isSameAs(validator);
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void listenersAndTokenValidatorAddedBeforeFromYamlAreRetained() throws IOException {
        Path dir = Files.createTempDirectory("builder-seed-");
        try {
            Path yaml = dir.resolve("application.yaml");
            Files.writeString(yaml, BASE_YAML);

            ServerEventListener listener = new ServerEventListener() {};
            TokenValidator validator = (token, namespace, topic) -> AuthDecision.accept("test");

            SocketIoServerSpec spec = SocketIoServer.builder()
                    .listener(listener)
                    .tokenValidator(validator)
                    .fromYaml(yaml)
                    .build();

            assertThat(spec.listeners()).containsExactly(listener);
            assertThat(spec.tokenValidator()).isSameAs(validator);
        } finally {
            deleteRecursively(dir);
        }
    }

    private static Path spillConfigPath() {
        try {
            return Path.of(Objects.requireNonNull(
                    SocketIoServerBuilderFromYamlTest.class.getResource("/spill-to-disk-config.yaml"))
                    .toURI());
        } catch (Exception exception) {
            throw new AssertionError("spill-to-disk-config.yaml must be available on the test classpath", exception);
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
