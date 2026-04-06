package io.streamfence.internal.delivery;

import io.streamfence.DeliveryMode;
import io.streamfence.internal.config.TopicPolicy;
import io.streamfence.internal.observability.ServerEventPublisher;
import io.streamfence.ServerMetrics;
import io.streamfence.internal.protocol.OutboundTopicMessage;
import io.streamfence.internal.protocol.TopicMessageEnvelope;
import io.streamfence.internal.protocol.TopicMessageMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TopicDispatcher implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TopicDispatcher.class);

    private final TopicRegistry topicRegistry;
    private final ClientSessionRegistry sessionRegistry;
    private final AckTracker ackTracker;
    private final RetryService retryService;
    private final ServerMetrics metrics;
    private final ExecutorService senderExecutor;
    private final ObjectMapper objectMapper;
    private final ServerEventPublisher eventPublisher;

    public TopicDispatcher(
            TopicRegistry topicRegistry,
            ClientSessionRegistry sessionRegistry,
            AckTracker ackTracker,
            RetryService retryService,
            ServerMetrics metrics,
            int workerThreads,
            ObjectMapper objectMapper
    ) {
        this(topicRegistry, sessionRegistry, ackTracker, retryService, metrics, workerThreads, objectMapper, ServerEventPublisher.noOp());
    }

    public TopicDispatcher(
            TopicRegistry topicRegistry,
            ClientSessionRegistry sessionRegistry,
            AckTracker ackTracker,
            RetryService retryService,
            ServerMetrics metrics,
            int workerThreads,
            ObjectMapper objectMapper,
            ServerEventPublisher eventPublisher
    ) {
        this.topicRegistry = topicRegistry;
        this.sessionRegistry = sessionRegistry;
        this.ackTracker = ackTracker;
        this.retryService = retryService;
        this.metrics = metrics;
        this.senderExecutor = Executors.newFixedThreadPool(Math.max(2, workerThreads));
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher == null ? ServerEventPublisher.noOp() : eventPublisher;
    }

    public void publish(String namespace, String topic, Object payload) {
        TopicPolicy topicPolicy = topicRegistry.find(namespace, topic)
                .orElseThrow(() -> new IllegalArgumentException("Unknown topic " + namespace + "/" + topic));

        boolean ackRequired = topicPolicy.deliveryMode() == DeliveryMode.AT_LEAST_ONCE;
        String messageId = UUID.randomUUID().toString();
        OutboundTopicMessage outboundTopicMessage = createOutboundMessage(topicPolicy, messageId, ackRequired, payload);
        PublishedMessage publishedMessage = new PublishedMessage(
                outboundTopicMessage,
                topicPolicy.coalesce() ? topicPolicy.topic() : null
        );

        for (ClientSessionState sessionState : sessionRegistry.subscribersOf(namespace, topic)) {
            enqueueToSession(sessionState, topicPolicy, publishedMessage);
        }

        metrics.recordPublish(namespace, topic, publishedMessage.estimatedBytes());
    }

    /**
     * Targeted publish to a single client. The client must be connected and
     * subscribed to {@code topic}; otherwise the call is a silent no-op
     * (unknown client) or emits a warn and returns (known client but not
     * subscribed). Reuses the exact enqueue/retry/overflow/send pipeline that
     * client-originated publishes flow through.
     */
    public void publishTo(String namespace, String clientId, String topic, Object payload) {
        TopicPolicy topicPolicy = topicRegistry.find(namespace, topic)
                .orElseThrow(() -> new IllegalArgumentException("Unknown topic " + namespace + "/" + topic));

        ClientSessionState sessionState = sessionRegistry.get(clientId);
        if (sessionState == null) {
            // Matches existing dispatch behavior when a subscriber disappears
            // mid-flight: drop silently.
            return;
        }
        if (!sessionState.namespace().equals(namespace) || !sessionState.isSubscribed(topic)) {
            LOG.warn("event=publish_to_not_subscribed namespace={} clientId={} topic={}",
                    namespace, clientId, topic);
            return;
        }

        boolean ackRequired = topicPolicy.deliveryMode() == DeliveryMode.AT_LEAST_ONCE;
        String messageId = UUID.randomUUID().toString();
        OutboundTopicMessage outboundTopicMessage = createOutboundMessage(topicPolicy, messageId, ackRequired, payload);
        PublishedMessage publishedMessage = new PublishedMessage(
                outboundTopicMessage,
                topicPolicy.coalesce() ? topicPolicy.topic() : null
        );

        enqueueToSession(sessionState, topicPolicy, publishedMessage);
        metrics.recordPublish(namespace, topic, publishedMessage.estimatedBytes());
    }

    public void onClientDisconnected(String clientId) {
        ackTracker.removeClient(clientId);
    }

    public void onClientUnsubscribed(String clientId, String namespace, String topic) {
        ClientSessionState sessionState = sessionRegistry.get(clientId);
        if (sessionState != null) {
            sessionRegistry.unsubscribe(sessionState, topic);
        }
        ackTracker.removeClientTopic(clientId, namespace, topic);
    }

    public void acknowledge(String clientId, String namespace, String topic, String messageId) {
        boolean acknowledged = ackTracker.acknowledge(clientId, namespace, topic, messageId);
        ClientSessionState sessionState = sessionRegistry.get(clientId);
        if (!acknowledged || sessionState == null) {
            return;
        }
        ClientLane lane = sessionState.lane(topic);
        if (lane != null) {
            LaneEntry removed = lane.removeByMessageId(messageId);
            if (removed != null && lane.hasPendingSend()) {
                scheduleDrain(sessionState, topic);
            }
        }
    }

    public void processRetries() {
        for (RetryDecision decision : retryService.scan(Instant.now())) {
            if (decision.action() == RetryAction.EXHAUSTED) {
                metrics.recordRetryExhausted(decision.namespace(), decision.topic());
                eventPublisher.retryExhausted(
                        decision.namespace(),
                        decision.clientId(),
                        decision.topic(),
                        decision.pendingMessage().messageId(),
                        decision.pendingMessage().retryCount());
                LOG.warn("event=retry_exhausted namespace={} topic={} clientId={} messageId={}",
                        decision.namespace(), decision.topic(), decision.clientId(), decision.pendingMessage().messageId());
                ClientSessionState sessionState = sessionRegistry.get(decision.clientId());
                if (sessionState != null) {
                    ClientLane lane = sessionState.lane(decision.topic());
                    if (lane != null) {
                        LaneEntry removed = lane.removeByMessageId(decision.pendingMessage().messageId());
                        if (removed != null && lane.hasPendingSend()) {
                            scheduleDrain(sessionState, decision.topic());
                        }
                    }
                }
                continue;
            }

            LOG.debug("event=ack_timeout namespace={} topic={} clientId={} messageId={} retryCount={}",
                    decision.namespace(), decision.topic(), decision.clientId(), decision.pendingMessage().messageId(),
                    decision.pendingMessage().retryCount());
            ClientSessionState sessionState = sessionRegistry.get(decision.clientId());
            if (sessionState == null) {
                continue;
            }
            resend(sessionState, decision.topic(), decision.pendingMessage());
            metrics.recordRetry(decision.namespace(), decision.topic());
            eventPublisher.retry(
                    decision.namespace(),
                    decision.clientId(),
                    decision.topic(),
                    decision.pendingMessage().messageId(),
                    decision.pendingMessage().retryCount());
            LOG.debug("event=retry namespace={} topic={} clientId={} messageId={} retryCount={}",
                    decision.namespace(), decision.topic(), decision.clientId(), decision.pendingMessage().messageId(),
                    decision.pendingMessage().retryCount());
        }
    }

    private OutboundTopicMessage createOutboundMessage(TopicPolicy topicPolicy, String messageId, boolean ackRequired, Object payload) {
        TopicMessageMetadata metadata = new TopicMessageMetadata(
                topicPolicy.namespace(),
                topicPolicy.topic(),
                messageId,
                ackRequired
        );
        TopicMessageEnvelope envelope = new TopicMessageEnvelope(metadata, payload);
        return new OutboundTopicMessage("topic-message", metadata, new Object[]{envelope}, estimateBytes(envelope));
    }

    private long estimateBytes(Object value) {
        CountingOutputStream counter = new CountingOutputStream();
        try {
            objectMapper.writeValue(counter, value);
        } catch (IOException ignored) {
            return 1;
        }
        return Math.max(1, counter.count());
    }

    private static final class CountingOutputStream extends OutputStream {
        private long count;

        @Override
        public void write(int b) {
            count++;
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            count += length;
        }

        long count() {
            return count;
        }
    }

    private void enqueueToSession(ClientSessionState sessionState, TopicPolicy topicPolicy, PublishedMessage publishedMessage) {
        ClientLane lane = sessionState.lane(topicPolicy.topic(), topicPolicy);
        LaneEntry laneEntry = new LaneEntry(publishedMessage);
        EnqueueResult result = lane.enqueue(laneEntry);
        switch (result.status()) {
            case ACCEPTED, REPLACED_SNAPSHOT -> scheduleDrain(sessionState, topicPolicy.topic());
            case COALESCED -> {
                metrics.recordCoalesced(topicPolicy.namespace(), topicPolicy.topic());
                LOG.debug("event=coalesced namespace={} topic={} clientId={} reason={}",
                        topicPolicy.namespace(), topicPolicy.topic(), sessionState.clientId(), result.reason());
                scheduleDrain(sessionState, topicPolicy.topic());
            }
            case DROPPED_OLDEST_AND_ACCEPTED -> {
                metrics.recordDropped(topicPolicy.namespace(), topicPolicy.topic());
                LOG.debug("event=queue_drop_oldest namespace={} topic={} clientId={} reason={}",
                        topicPolicy.namespace(), topicPolicy.topic(), sessionState.clientId(), result.reason());
                scheduleDrain(sessionState, topicPolicy.topic());
            }
            case REJECTED -> {
                metrics.recordQueueOverflow(topicPolicy.namespace(), topicPolicy.topic(), result.reason());
                eventPublisher.queueOverflow(topicPolicy.namespace(), sessionState.clientId(), topicPolicy.topic(), result.reason());
                LOG.debug("event=queue_overflow namespace={} topic={} clientId={} reason={}",
                        topicPolicy.namespace(), topicPolicy.topic(), sessionState.clientId(), result.reason());
            }
        }
    }

    private void scheduleDrain(ClientSessionState sessionState, String topic) {
        if (senderExecutor.isShutdown()) {
            return;
        }
        if (!sessionState.startDrain(topic)) {
            return;
        }
        try {
            senderExecutor.execute(() -> drainTopic(sessionState, topic));
        } catch (RejectedExecutionException exception) {
            sessionState.finishDrain(topic);
            if (!senderExecutor.isShutdown()) {
                throw exception;
            }
        }
    }

    private void drainTopic(ClientSessionState sessionState, String topic) {
        try {
            ClientLane lane = sessionState.lane(topic);
            if (lane == null) {
                return;
            }

            int maxInFlight = lane.topicPolicy().maxInFlight();

            while (true) {
                if (!lane.hasPendingSend()) {
                    return;
                }

                LaneEntry laneEntry;
                if (lane.topicPolicy().deliveryMode() == DeliveryMode.AT_LEAST_ONCE) {
                    if (lane.inFlightCount() >= maxInFlight) {
                        return;
                    }
                    laneEntry = lane.firstPendingSend();
                    if (laneEntry == null) {
                        return;
                    }
                } else {
                    laneEntry = lane.peek();
                }

                if (laneEntry.ackRequired()) {
                    lane.markAwaiting(laneEntry);
                    ackTracker.register(
                            sessionState.clientId(),
                            sessionState.namespace(),
                            topic,
                            laneEntry,
                            Duration.ofMillis(lane.topicPolicy().ackTimeoutMs()),
                            lane.topicPolicy().maxRetries(),
                            Instant.now()
                    );
                    sendReliable(sessionState, topic, laneEntry, lane);
                    continue;
                }

                lane.poll();
                send(sessionState, laneEntry);
            }
        } finally {
            sessionState.finishDrain(topic);
            ClientLane lane = sessionState.lane(topic);
            if (lane != null && lane.hasPendingSend()) {
                scheduleDrain(sessionState, topic);
            }
        }
    }

    private void resend(ClientSessionState sessionState, String topic, LaneEntry laneEntry) {
        ClientLane lane = sessionState.lane(topic);
        if (lane == null) {
            return;
        }
        LaneEntry pending = lane.findByMessageId(laneEntry.messageId());
        if (pending == null) {
            return;
        }
        send(sessionState, pending);
    }

    private void send(ClientSessionState sessionState, LaneEntry laneEntry) {
        try {
            sessionState.client().sendEvent(
                    laneEntry.outboundMessage().eventName(),
                    laneEntry.outboundMessage().eventArguments()
            );
        } catch (RuntimeException exception) {
            LOG.error("event=send_failure namespace={} topic={} clientId={} messageId={} message={}",
                    sessionState.namespace(), laneEntry.topic(), sessionState.clientId(),
                    laneEntry.messageId(), exception.getMessage(), exception);
        }
    }

    private void sendReliable(ClientSessionState sessionState, String topic, LaneEntry laneEntry, ClientLane lane) {
        try {
            sessionState.client().sendEvent(
                    laneEntry.outboundMessage().eventName(),
                    laneEntry.outboundMessage().eventArguments()
            );
        } catch (RuntimeException exception) {
            LOG.error("event=send_failure namespace={} topic={} clientId={} messageId={} message={}",
                    sessionState.namespace(), topic, sessionState.clientId(),
                    laneEntry.messageId(), exception.getMessage(), exception);
            ackTracker.acknowledge(sessionState.clientId(), sessionState.namespace(), topic, laneEntry.messageId());
            lane.removeByMessageId(laneEntry.messageId());
        }
    }

    @Override
    public void close() {
        senderExecutor.shutdown();
        try {
            if (!senderExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                senderExecutor.shutdownNow();
                if (!senderExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    LOG.warn("event=sender_executor_forced_shutdown reason=awaitTermination_timeout");
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            senderExecutor.shutdownNow();
        }
    }
}
