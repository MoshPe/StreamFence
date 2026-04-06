package io.streamfence.sim;

import io.streamfence.DeliveryMode;
import io.streamfence.OverflowAction;
import io.streamfence.internal.config.TopicPolicy;
import io.streamfence.internal.protocol.OutboundTopicMessage;
import io.streamfence.internal.protocol.TopicMessageEnvelope;
import io.streamfence.internal.protocol.TopicMessageMetadata;
import io.streamfence.internal.delivery.ClientLane;
import io.streamfence.internal.delivery.EnqueueStatus;
import io.streamfence.internal.delivery.LaneEntry;
import io.streamfence.internal.delivery.PublishedMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class LoadSimulationHarness {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public SimulationResult run(int clientCount, int messagesPerClient) {
        TopicPolicy bestEffort = new TopicPolicy("/non-reliable", "prices", DeliveryMode.BEST_EFFORT, OverflowAction.COALESCE,
                8, 4096, 1000, 0, true, true, false, 1);
        TopicPolicy reliable = new TopicPolicy("/reliable", "alerts", DeliveryMode.AT_LEAST_ONCE, OverflowAction.REJECT_NEW,
                16, 8192, 1000, 2, false, true, false, 4);

        List<ClientLane> lanes = new ArrayList<>();
        for (int index = 0; index < clientCount; index++) {
            lanes.add(new ClientLane(index % 2 == 0 ? bestEffort : reliable));
        }

        int accepted = 0;
        int rejected = 0;
        int dropped = 0;
        int coalesced = 0;

        for (int client = 0; client < clientCount; client++) {
            ClientLane lane = lanes.get(client);
            TopicPolicy topicPolicy = lane.topicPolicy();
            for (int message = 0; message < messagesPerClient; message++) {
                long bytes = ThreadLocalRandom.current().nextLong(128, 768);
                String messageId = topicPolicy.topic() + '-' + client + '-' + message;
                TopicMessageMetadata metadata = new TopicMessageMetadata(topicPolicy.namespace(), topicPolicy.topic(), messageId,
                        topicPolicy.deliveryMode() == DeliveryMode.AT_LEAST_ONCE);
                TopicMessageEnvelope envelope = new TopicMessageEnvelope(metadata, OBJECT_MAPPER.createObjectNode().put("message", message));
                OutboundTopicMessage outboundTopicMessage = new OutboundTopicMessage("topic-message", metadata, new Object[]{envelope}, bytes);
                LaneEntry laneEntry = new LaneEntry(new PublishedMessage(outboundTopicMessage, topicPolicy.coalesce() ? topicPolicy.topic() : null));
                EnqueueStatus status = lane.enqueue(laneEntry).status();
                if (status == EnqueueStatus.ACCEPTED || status == EnqueueStatus.REPLACED_SNAPSHOT) {
                    accepted++;
                } else if (status == EnqueueStatus.REJECTED) {
                    rejected++;
                } else if (status == EnqueueStatus.DROPPED_OLDEST_AND_ACCEPTED) {
                    accepted++;
                    dropped++;
                } else if (status == EnqueueStatus.COALESCED) {
                    accepted++;
                    coalesced++;
                }
            }
        }

        return new SimulationResult(clientCount, messagesPerClient, accepted, rejected, dropped, coalesced);
    }

    public record SimulationResult(
            int clientCount,
            int messagesPerClient,
            int accepted,
            int rejected,
            int dropped,
            int coalesced
    ) {
    }
}

