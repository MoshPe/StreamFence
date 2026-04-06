package io.streamfence.internal.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.streamfence.internal.protocol.OutboundTopicMessage;
import io.streamfence.internal.protocol.TopicMessageEnvelope;
import io.streamfence.internal.protocol.TopicMessageMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AckTrackerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void acknowledgeRemovesPendingMessage() {
        AckTracker ackTracker = new AckTracker();
        LaneEntry pendingMessage = message("m1");
        Instant now = Instant.parse("2026-04-03T12:00:00Z");

        ackTracker.register("client-1", "/reliable", "alerts", pendingMessage, Duration.ofSeconds(2), 3, now);
        boolean acknowledged = ackTracker.acknowledge("client-1", "/reliable", "alerts", "m1");

        assertThat(acknowledged).isTrue();
        assertThat(ackTracker.pendingCount()).isZero();
    }

    @Test
    void expiredMessageProducesRetryDecision() {
        AckTracker ackTracker = new AckTracker();
        LaneEntry pendingMessage = message("m1");
        Instant now = Instant.parse("2026-04-03T12:00:00Z");

        ackTracker.register("client-1", "/reliable", "alerts", pendingMessage, Duration.ofSeconds(2), 2, now);
        var decisions = ackTracker.collectExpired(now.plusSeconds(3));

        assertThat(decisions).hasSize(1);
        assertThat(decisions.getFirst().action()).isEqualTo(RetryAction.RETRY);
        assertThat(decisions.getFirst().pendingMessage().retryCount()).isEqualTo(1);
        assertThat(ackTracker.pendingCount()).isEqualTo(1);
    }

    @Test
    void exhaustedMessageIsReportedAndRemoved() {
        AckTracker ackTracker = new AckTracker();
        LaneEntry pendingMessage = message("m1");
        Instant now = Instant.parse("2026-04-03T12:00:00Z");

        ackTracker.register("client-1", "/reliable", "alerts", pendingMessage, Duration.ofSeconds(2), 1, now);
        ackTracker.collectExpired(now.plusSeconds(3));

        var decisions = ackTracker.collectExpired(now.plusSeconds(6));

        assertThat(decisions).hasSize(1);
        assertThat(decisions.getFirst().action()).isEqualTo(RetryAction.EXHAUSTED);
        assertThat(ackTracker.pendingCount()).isZero();
    }

    private static LaneEntry message(String messageId) {
        TopicMessageMetadata metadata = new TopicMessageMetadata("/reliable", "alerts", messageId, true);
        TopicMessageEnvelope envelope = new TopicMessageEnvelope(metadata, OBJECT_MAPPER.createObjectNode().put("id", messageId));
        OutboundTopicMessage outboundTopicMessage = new OutboundTopicMessage("topic-message", metadata, new Object[]{envelope}, 128);
        return new LaneEntry(new PublishedMessage(outboundTopicMessage, null));
    }
}
