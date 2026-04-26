package io.streamfence;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class NamespaceSpecTest {

    @Test
    void acceptsSpillToDiskForBestEffortNamespaces() {
        NamespaceSpec namespace = NamespaceSpec.builder("/feed")
                .authRequired(false)
                .deliveryMode(DeliveryMode.BEST_EFFORT)
                .overflowAction(OverflowAction.SPILL_TO_DISK)
                .maxQueuedMessagesPerClient(16)
                .maxQueuedBytesPerClient(65_536)
                .ackTimeoutMs(1_000)
                .maxRetries(0)
                .coalesce(false)
                .allowPolling(true)
                .maxInFlight(1)
                .topics(List.of("prices"))
                .build();

        assertThat(namespace.overflowAction()).isEqualTo(OverflowAction.SPILL_TO_DISK);
        assertThat(namespace.deliveryMode()).isEqualTo(DeliveryMode.BEST_EFFORT);
    }

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
    void rejectsCoalesceOverflowForAtLeastOnceNamespaces() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> minimalAtLeastOnce().overflowAction(OverflowAction.COALESCE).build())
                .withMessageContaining("REJECT_NEW or SPILL_TO_DISK");
    }

    @Test
    void rejectsSnapshotOnlyOverflowForAtLeastOnceNamespaces() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> minimalAtLeastOnce().overflowAction(OverflowAction.SNAPSHOT_ONLY).build())
                .withMessageContaining("REJECT_NEW or SPILL_TO_DISK");
    }

    @Test
    void rejectsCoalesceEnabledForAtLeastOnceNamespaces() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> minimalAtLeastOnce().coalesce(true).build())
                .withMessageContaining("coalescing");
    }

    @Test
    void acceptsSpillToDiskForAtLeastOnceNamespaces() {
        NamespaceSpec namespace = NamespaceSpec.builder("/reliable")
                .authRequired(true)
                .deliveryMode(DeliveryMode.AT_LEAST_ONCE)
                .overflowAction(OverflowAction.SPILL_TO_DISK)
                .maxQueuedMessagesPerClient(16)
                .maxQueuedBytesPerClient(65_536)
                .ackTimeoutMs(1_000)
                .maxRetries(3)
                .coalesce(false)
                .allowPolling(true)
                .maxInFlight(1)
                .topics(List.of("alerts"))
                .build();

        assertThat(namespace.overflowAction()).isEqualTo(OverflowAction.SPILL_TO_DISK);
        assertThat(namespace.deliveryMode()).isEqualTo(DeliveryMode.AT_LEAST_ONCE);
    }

    @Test
    void rejectsDropOldestForAtLeastOnceNamespaces() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> NamespaceSpec.builder("/reliable")
                        .deliveryMode(DeliveryMode.AT_LEAST_ONCE)
                        .overflowAction(OverflowAction.DROP_OLDEST)
                        .maxQueuedMessagesPerClient(16)
                        .maxQueuedBytesPerClient(65_536)
                        .ackTimeoutMs(1_000)
                        .maxRetries(3)
                        .coalesce(false)
                        .allowPolling(true)
                        .maxInFlight(1)
                        .topics(List.of("alerts"))
                        .build())
                .withMessageContaining("AT_LEAST_ONCE");
    }

    /** Returns a builder for a minimal valid AT_LEAST_ONCE namespace (REJECT_NEW, no coalesce). */
    private static NamespaceSpec.Builder minimalAtLeastOnce() {
        return NamespaceSpec.builder("/reliable")
                .deliveryMode(DeliveryMode.AT_LEAST_ONCE)
                .overflowAction(OverflowAction.REJECT_NEW)
                .maxQueuedMessagesPerClient(16)
                .maxQueuedBytesPerClient(65_536)
                .ackTimeoutMs(1_000)
                .maxRetries(3)
                .coalesce(false)
                .allowPolling(true)
                .maxInFlight(1)
                .topics(List.of("alerts"));
    }
}
