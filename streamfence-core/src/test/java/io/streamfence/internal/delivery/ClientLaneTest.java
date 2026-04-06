package io.streamfence.internal.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.streamfence.DeliveryMode;
import io.streamfence.OverflowAction;
import io.streamfence.internal.config.TopicPolicy;
import io.streamfence.internal.protocol.OutboundTopicMessage;
import io.streamfence.internal.protocol.TopicMessageEnvelope;
import io.streamfence.internal.protocol.TopicMessageMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    private static LaneEntry message(String messageId, long bytes, String coalesceKey) {
        TopicMessageMetadata metadata = new TopicMessageMetadata("/non-reliable", "prices", messageId, false);
        ObjectNode payload = OBJECT_MAPPER.createObjectNode().put("id", messageId);
        TopicMessageEnvelope envelope = new TopicMessageEnvelope(metadata, payload);
        OutboundTopicMessage outboundTopicMessage = new OutboundTopicMessage("topic-message", metadata, new Object[]{envelope}, bytes);
        return new LaneEntry(new PublishedMessage(outboundTopicMessage, coalesceKey));
    }
}

