package io.streamfence.internal.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.streamfence.DeliveryMode;
import io.streamfence.OverflowAction;
import io.streamfence.internal.config.TopicPolicy;
import io.streamfence.internal.protocol.OutboundTopicMessage;
import io.streamfence.internal.protocol.TopicMessageEnvelope;
import io.streamfence.internal.protocol.TopicMessageMetadata;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ClientLaneTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void enqueueAcceptsMessageWithinLimits() {
        ClientLane lane = new ClientLane(policy(OverflowAction.REJECT_NEW, 2, 128, false));

        EnqueueResult result = lane.enqueue(message("m1", 32, "prices"));

        assertThat(result.status()).isEqualTo(EnqueueStatus.ACCEPTED);
        assertThat(lane.size()).isEqualTo(1);
        assertThat(lane.queuedBytes()).isEqualTo(32);
    }

    @Test
    void rejectsNewMessageWhenCountLimitWouldBeExceeded() {
        ClientLane lane = new ClientLane(policy(OverflowAction.REJECT_NEW, 1, 128, false));
        lane.enqueue(message("m1", 32, "prices"));

        EnqueueResult result = lane.enqueue(message("m2", 32, "prices"));

        assertThat(result.status()).isEqualTo(EnqueueStatus.REJECTED);
        assertThat(result.reason()).contains("count");
        assertThat(lane.snapshot()).extracting(LaneEntry::messageId).containsExactly("m1");
    }

    @Test
    void rejectsNewMessageWhenByteLimitWouldBeExceeded() {
        ClientLane lane = new ClientLane(policy(OverflowAction.REJECT_NEW, 4, 48, false));
        lane.enqueue(message("m1", 32, "prices"));

        EnqueueResult result = lane.enqueue(message("m2", 32, "prices"));

        assertThat(result.status()).isEqualTo(EnqueueStatus.REJECTED);
        assertThat(result.reason()).contains("bytes");
        assertThat(lane.queuedBytes()).isEqualTo(32);
    }

    @Test
    void dropsOldestMessageWhenConfigured() {
        ClientLane lane = new ClientLane(policy(OverflowAction.DROP_OLDEST, 2, 64, false));
        lane.enqueue(message("m1", 32, "prices"));
        lane.enqueue(message("m2", 32, "prices"));

        EnqueueResult result = lane.enqueue(message("m3", 32, "prices"));

        assertThat(result.status()).isEqualTo(EnqueueStatus.DROPPED_OLDEST_AND_ACCEPTED);
        assertThat(lane.snapshot()).extracting(LaneEntry::messageId).containsExactly("m2", "m3");
        assertThat(lane.queuedBytes()).isEqualTo(64);
    }

    @Test
    void coalescesMatchingMessagesWhenConfigured() {
        ClientLane lane = new ClientLane(policy(OverflowAction.COALESCE, 2, 64, true));
        lane.enqueue(message("m1", 24, "prices"));

        EnqueueResult result = lane.enqueue(message("m2", 28, "prices"));

        assertThat(result.status()).isEqualTo(EnqueueStatus.COALESCED);
        assertThat(lane.snapshot()).extracting(LaneEntry::messageId).containsExactly("m2");
        assertThat(lane.queuedBytes()).isEqualTo(28);
    }

    @Test
    void snapshotOnlyKeepsLatestMessage() {
        ClientLane lane = new ClientLane(policy(OverflowAction.SNAPSHOT_ONLY, 4, 128, false));
        lane.enqueue(message("m1", 24, "prices"));
        lane.enqueue(message("m2", 24, "prices"));

        EnqueueResult result = lane.enqueue(message("m3", 24, "prices"));

        assertThat(result.status()).isEqualTo(EnqueueStatus.REPLACED_SNAPSHOT);
        assertThat(lane.snapshot()).extracting(LaneEntry::messageId).containsExactly("m3");
    }

    @Test
    void spillToDiskAcceptsOverflowAfterTheMemoryQueueFills() {
        ClientLane lane = new ClientLane(policy(OverflowAction.SPILL_TO_DISK, 2, 64, false));

        assertThat(lane.enqueue(message("m1", 32, "prices")).status()).isEqualTo(EnqueueStatus.ACCEPTED);
        assertThat(lane.enqueue(message("m2", 32, "prices")).status()).isEqualTo(EnqueueStatus.ACCEPTED);
        EnqueueResult overflowResult = lane.enqueue(message("m3", 32, "prices"));

        assertThat(overflowResult.status()).isEqualTo(EnqueueStatus.SPILLED);
        assertThat(drainIds(lane)).containsExactly("m1", "m2", "m3");
    }

    @Test
    void spillToDiskStillRejectsMessagesThatExceedThePerMessageByteLimit() {
        ClientLane lane = new ClientLane(policy(OverflowAction.SPILL_TO_DISK, 4, 48, false));

        EnqueueResult result = lane.enqueue(message("too-large", 64, "prices"));

        assertThat(result.status()).isEqualTo(EnqueueStatus.REJECTED);
        assertThat(result.reason()).contains("byte limit");
        assertThat(lane.snapshot()).isEmpty();
    }

    private static TopicPolicy policy(OverflowAction overflowAction, int maxCount, long maxBytes, boolean coalesce) {
        return new TopicPolicy(
                "/non-reliable",
                "prices",
                DeliveryMode.BEST_EFFORT,
                overflowAction,
                maxCount,
                maxBytes,
                1000,
                0,
                coalesce,
                true,
                false,
                1);
    }

    // ── new coverage tests ────────────────────────────────────────────────────

    @Test
    void firstPendingSendReturnsNullWhenAllEntriesAreAwaiting() {
        ClientLane lane = new ClientLane(policy(OverflowAction.REJECT_NEW, 4, 256, false));
        lane.enqueue(message("m1", 32, null));
        lane.enqueue(message("m2", 32, null));

        lane.markAwaiting(lane.peek());
        // Mark second entry awaiting too
        LaneEntry second = lane.firstPendingSend();
        if (second != null) {
            lane.markAwaiting(second);
        }

        assertThat(lane.firstPendingSend()).isNull();
    }

    @Test
    void removeByMessageIdReturnsNullWhenMessageIsNotQueued() {
        ClientLane lane = new ClientLane(policy(OverflowAction.REJECT_NEW, 4, 256, false));
        lane.enqueue(message("m1", 32, null));

        LaneEntry result = lane.removeByMessageId("does-not-exist");

        assertThat(result).isNull();
        assertThat(lane.size()).isEqualTo(1);
    }

    @Test
    void pollDecrementsInFlightCountWhenEntryWasAwaiting() {
        ClientLane lane = new ClientLane(policy(OverflowAction.REJECT_NEW, 4, 256, false));
        lane.enqueue(message("m1", 32, null));
        LaneEntry entry = lane.peek();
        lane.markAwaiting(entry);
        assertThat(lane.inFlightCount()).isEqualTo(1);

        lane.poll(); // onEntryRemoved should decrement inFlightCount

        assertThat(lane.inFlightCount()).isEqualTo(0);
    }

    @Test
    void closeReleasesQueuedStateOnNonSpillLane() {
        ClientLane lane = new ClientLane(policy(OverflowAction.REJECT_NEW, 4, 256, false));
        lane.enqueue(message("m1", 32, null));
        lane.enqueue(message("m2", 32, null));

        lane.close();

        assertThat(lane.size()).isEqualTo(0);
        assertThat(lane.queuedBytes()).isEqualTo(0);
        assertThat(lane.inFlightCount()).isEqualTo(0);
    }

    @Test
    void tryCoalesceRejectsWhenByteLimitWouldBeExceeded() {
        // maxBytes=48: m1 takes 32 bytes; coalescing m2 (40 bytes) would need 40 > 48? No.
        // Need: existing=32, incoming=40, maxBytes=48 -> recalculated=40 which is < 48. That passes.
        // Instead: existing=32, incoming=48, maxBytes=48 -> recalculated=48 = 48. Still passes.
        // existing=32, incoming=50, maxBytes=48 -> recalculated=50 > 48. Rejected.
        ClientLane lane = new ClientLane(policy(OverflowAction.COALESCE, 4, 48, true));
        lane.enqueue(message("m1", 32, "key"));

        // m2 with same coalesce key but replacement would push bytes over limit
        EnqueueResult result = lane.enqueue(message("m2", 50, "key"));

        assertThat(result.status()).isEqualTo(EnqueueStatus.REJECTED);
        assertThat(result.reason()).contains("byte limit");
        // Original m1 must still be in queue
        assertThat(lane.snapshot()).extracting(LaneEntry::messageId).containsExactly("m1");
    }

    @Test
    void dropOldestRejectsWhenSingleMessageAloneExceedsByteCap() {
        // Byte cap is 30; message is 40 — it can never fit even after emptying the queue
        ClientLane lane = new ClientLane(policy(OverflowAction.DROP_OLDEST, 4, 30, false));
        lane.enqueue(message("m1", 28, null));

        EnqueueResult result = lane.enqueue(message("m2", 40, null));

        assertThat(result.status()).isEqualTo(EnqueueStatus.REJECTED);
    }

    @Test
    void spillToDiskWithNullSpillRootUsesTemporaryDirectory() {
        // null spillRoot → ClientLane creates a temp directory via Files.createTempDirectory
        ClientLane lane = new ClientLane(
                policy(OverflowAction.SPILL_TO_DISK, 1, 64, false),
                null);  // explicit null spillRoot

        assertThat(lane.enqueue(message("m1", 32, null)).status()).isEqualTo(EnqueueStatus.ACCEPTED);
        assertThat(lane.enqueue(message("m2", 32, null)).status()).isEqualTo(EnqueueStatus.SPILLED);
        assertThat(drainIds(lane)).containsExactly("m1", "m2");

        lane.close();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static java.util.List<String> drainIds(ClientLane lane) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        LaneEntry entry;
        while ((entry = lane.poll()) != null) {
            ids.add(entry.messageId());
        }
        return ids;
    }

    private static LaneEntry message(String messageId, long bytes, String coalesceKey) {
        TopicMessageMetadata metadata = new TopicMessageMetadata("/non-reliable", "prices", messageId, false);
        ObjectNode payload = OBJECT_MAPPER.createObjectNode().put("id", messageId);
        TopicMessageEnvelope envelope = new TopicMessageEnvelope(metadata, payload);
        OutboundTopicMessage outboundTopicMessage = new OutboundTopicMessage("topic-message", metadata, new Object[]{envelope}, bytes);
        return new LaneEntry(new PublishedMessage(outboundTopicMessage, coalesceKey));
    }
}

