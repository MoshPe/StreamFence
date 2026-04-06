package io.streamfence;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;

class NamespaceSpecTest {

    @Test
    void rejectsDuplicateTopicNames() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> NamespaceSpec.builder("/feed")
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
                        .topics(List.of("prices", "prices"))
                        .build())
                .withMessageContaining("duplicate topic");
    }

    @Test
    void rejectsInvalidReliableNamespaceSettings() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> NamespaceSpec.builder("/reliable")
                        .authRequired(true)
                        .deliveryMode(DeliveryMode.AT_LEAST_ONCE)
                        .overflowAction(OverflowAction.COALESCE)
                        .maxQueuedMessagesPerClient(16)
                        .maxQueuedBytesPerClient(65_536)
                        .ackTimeoutMs(1_000)
                        .maxRetries(0)
                        .coalesce(true)
                        .allowPolling(true)
                        .maxInFlight(1)
                        .topics(List.of("alerts"))
                        .build())
                .withMessageContaining("AT_LEAST_ONCE");
    }
}
