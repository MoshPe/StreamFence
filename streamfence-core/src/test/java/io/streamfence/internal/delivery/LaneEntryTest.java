package io.streamfence.internal.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.streamfence.internal.protocol.OutboundTopicMessage;
import io.streamfence.internal.protocol.TopicMessageEnvelope;
import io.streamfence.internal.protocol.TopicMessageMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LaneEntryTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void laneEntriesSharePublishedMessageButKeepIndependentAckState() {
        TopicMessageMetadata metadata = new TopicMessageMetadata("/non-reliable", "prices", "m1", true);
        TopicMessageEnvelope envelope = new TopicMessageEnvelope(metadata, OBJECT_MAPPER.createObjectNode().put("value", 42));
        OutboundTopicMessage outboundTopicMessage = new OutboundTopicMessage("topic-message", metadata, new Object[]{envelope}, 64);
        PublishedMessage publishedMessage = new PublishedMessage(outboundTopicMessage, "prices");

        LaneEntry first = new LaneEntry(publishedMessage);
        LaneEntry second = new LaneEntry(publishedMessage);

        first.markAwaitingAck(true);
        first.incrementRetryCount();

        assertThat(first.publishedMessage()).isSameAs(second.publishedMessage());
        assertThat(first.outboundMessage()).isSameAs(second.outboundMessage());
        assertThat(first.estimatedBytes()).isEqualTo(64);
        assertThat(second.awaitingAck()).isFalse();
        assertThat(second.retryCount()).isZero();
        assertThat(second.coalesceKey()).isEqualTo("prices");
    }
}
