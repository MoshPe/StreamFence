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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpillToDiskLaneTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void replaysSpilledMessagesInFifoOrderAfterTheMemoryQueueDrains() {
        ClientLane lane = new ClientLane(policy(OverflowAction.SPILL_TO_DISK, 2, 64, false));

        lane.enqueue(message("m1", 32));
        lane.enqueue(message("m2", 32));
        lane.enqueue(message("m3", 32));

        assertThat(drainIds(lane)).containsExactly("m1", "m2", "m3");
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

    private static List<String> drainIds(ClientLane lane) {
        List<String> ids = new ArrayList<>();
        LaneEntry entry;
        while ((entry = lane.poll()) != null) {
            ids.add(entry.messageId());
        }
        return ids;
    }

    private static LaneEntry message(String messageId, long bytes) {
        TopicMessageMetadata metadata = new TopicMessageMetadata("/non-reliable", "prices", messageId, false);
        ObjectNode payload = OBJECT_MAPPER.createObjectNode().put("id", messageId);
        TopicMessageEnvelope envelope = new TopicMessageEnvelope(metadata, payload);
        OutboundTopicMessage outboundTopicMessage = new OutboundTopicMessage("topic-message", metadata, new Object[]{envelope}, bytes);
        return new LaneEntry(new PublishedMessage(outboundTopicMessage, null));
    }
}
