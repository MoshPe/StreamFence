package io.streamfence.internal.delivery;

import io.streamfence.internal.protocol.OutboundTopicMessage;
import java.util.Objects;

public final class LaneEntry {

    private final PublishedMessage publishedMessage;
    // Mutated from the dispatcher sender thread, the AckTracker scan thread, and
    // the namespace event thread. volatile is sufficient because each writer
    // fully owns a particular transition (send -> mark awaiting, ack -> clear,
    // retry scan -> increment). No read-modify-write is performed concurrently.
    private volatile int retryCount;
    private volatile boolean awaitingAck;

    public LaneEntry(PublishedMessage publishedMessage) {
        this.publishedMessage = Objects.requireNonNull(publishedMessage, "publishedMessage");
    }

    public PublishedMessage publishedMessage() {
        return publishedMessage;
    }

    public String messageId() {
        return publishedMessage.messageId();
    }

    public OutboundTopicMessage outboundMessage() {
        return publishedMessage.outboundMessage();
    }

    public long estimatedBytes() {
        return publishedMessage.estimatedBytes();
    }

    public String coalesceKey() {
        return publishedMessage.coalesceKey();
    }

    public String namespace() {
        return publishedMessage.namespace();
    }

    public String topic() {
        return publishedMessage.topic();
    }

    public boolean ackRequired() {
        return publishedMessage.ackRequired();
    }

    public int retryCount() {
        return retryCount;
    }

    public int incrementRetryCount() {
        retryCount++;
        return retryCount;
    }

    public boolean awaitingAck() {
        return awaitingAck;
    }

    public void markAwaitingAck(boolean awaitingAck) {
        this.awaitingAck = awaitingAck;
    }
}
