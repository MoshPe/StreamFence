package io.streamfence.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.streamfence.AuthMode;
import io.streamfence.DeliveryMode;
import io.streamfence.OverflowAction;
import io.streamfence.TransportMode;
import io.streamfence.NamespaceSpec;
import io.streamfence.SocketIoServerSpec;
import java.util.List;
import java.util.Map;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

class SocketIoServerSpecMapperTest {

    @Test
    void toServerConfigExpandsNamespaceTopicsIntoInternalTopicPolicies() {
        NamespaceSpec namespace = NamespaceSpec.builder("/feed")
                .authRequired(false)
                .deliveryMode(DeliveryMode.BEST_EFFORT)
                .overflowAction(OverflowAction.REJECT_NEW)
                .maxQueuedMessagesPerClient(16)
                .maxQueuedBytesPerClient(65_536)
                .ackTimeoutMs(1_000)
                .maxRetries(0)
                .coalesce(false)
                .allowPolling(true)
                .maxInFlight(1)
                .topics(List.of("prices", "quotes"))
                .build();
        SocketIoServerSpec spec = new SocketIoServerSpec(
                "127.0.0.1",
                9092,
                TransportMode.WS,
                null,
                20_000,
                40_000,
                5_242_880,
                5_242_880,
                true,
                false,
                null,
                AuthMode.NONE,
                Map.of(),
                List.of(namespace),
                "0.0.0.0",
                0,
                10_000,
                0,
                60_000,
                20,
                null,
                List.of());

        ServerConfig config = SocketIoServerSpecMapper.toServerConfig(spec);

        assertThat(config.topicPolicies())
                .hasSize(2)
                .extracting(
                        TopicPolicy::namespace,
                        TopicPolicy::topic,
                        TopicPolicy::deliveryMode,
                        TopicPolicy::overflowAction,
                        TopicPolicy::maxQueuedMessagesPerClient,
                        TopicPolicy::maxQueuedBytesPerClient,
                        TopicPolicy::ackTimeoutMs,
                        TopicPolicy::maxRetries,
                        TopicPolicy::coalesce,
                        TopicPolicy::allowPolling,
                        TopicPolicy::authRequired,
                        TopicPolicy::maxInFlight)
                .containsExactlyInAnyOrder(
                        Tuple.tuple("/feed", "prices", DeliveryMode.BEST_EFFORT,
                                OverflowAction.REJECT_NEW, 16, 65_536L, 1_000L, 0, false, true, false, 1),
                        Tuple.tuple("/feed", "quotes", DeliveryMode.BEST_EFFORT,
                                OverflowAction.REJECT_NEW, 16, 65_536L, 1_000L, 0, false, true, false, 1));
    }

    @Test
    void fromServerConfigCollapsesUniformTopicPoliciesIntoNamespaceSpec() {
        ServerConfig config = new ServerConfig(
                "127.0.0.1",
                9092,
                TransportMode.WS,
                null,
                20_000,
                40_000,
                5_242_880,
                5_242_880,
                true,
                false,
                null,
                AuthMode.NONE,
                Map.of(),
                Map.of("/feed", new NamespaceConfig(false)),
                List.of(
                        new TopicPolicy("/feed", "prices", DeliveryMode.BEST_EFFORT,
                                OverflowAction.REJECT_NEW, 16, 65_536, 1_000, 0, false, true, false, 1),
                        new TopicPolicy("/feed", "quotes", DeliveryMode.BEST_EFFORT,
                                OverflowAction.REJECT_NEW, 16, 65_536, 1_000, 0, false, true, false, 1)
                ),
                "0.0.0.0",
                0,
                10_000,
                0,
                60_000,
                20
        );

        SocketIoServerSpec spec = SocketIoServerSpecMapper.fromServerConfig(config);

        assertThat(spec.namespaces())
                .singleElement()
                .satisfies(namespace -> {
                    assertThat(namespace.path()).isEqualTo("/feed");
                    assertThat(namespace.authRequired()).isFalse();
                    assertThat(namespace.topics()).containsExactly("prices", "quotes");
                    assertThat(namespace.deliveryMode()).isEqualTo(DeliveryMode.BEST_EFFORT);
                    assertThat(namespace.overflowAction()).isEqualTo(OverflowAction.REJECT_NEW);
                    assertThat(namespace.maxQueuedMessagesPerClient()).isEqualTo(16);
                    assertThat(namespace.maxQueuedBytesPerClient()).isEqualTo(65_536L);
                    assertThat(namespace.ackTimeoutMs()).isEqualTo(1_000L);
                    assertThat(namespace.maxRetries()).isZero();
                    assertThat(namespace.coalesce()).isFalse();
                    assertThat(namespace.allowPolling()).isTrue();
                    assertThat(namespace.maxInFlight()).isEqualTo(1);
                });
    }

    @Test
    void fromServerConfigRejectsMixedTopicPoliciesInsideNamespace() {
        ServerConfig config = new ServerConfig(
                "127.0.0.1",
                9092,
                TransportMode.WS,
                null,
                20_000,
                40_000,
                5_242_880,
                5_242_880,
                true,
                false,
                null,
                AuthMode.NONE,
                Map.of(),
                Map.of("/feed", new NamespaceConfig(false)),
                List.of(
                        new TopicPolicy("/feed", "prices", DeliveryMode.BEST_EFFORT,
                                OverflowAction.REJECT_NEW, 16, 65_536, 1_000, 0, false, true, false, 1),
                        new TopicPolicy("/feed", "quotes", DeliveryMode.BEST_EFFORT,
                                OverflowAction.COALESCE, 16, 65_536, 1_000, 0, true, true, false, 1)
                ),
                "0.0.0.0",
                0,
                10_000,
                0,
                60_000,
                20
        );

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SocketIoServerSpecMapper.fromServerConfig(config))
                .withMessageContaining("/feed");
    }
}
