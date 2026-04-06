package io.streamfence.internal.delivery;

import io.streamfence.internal.protocol.OutboundTopicMessage;
import java.util.Objects;

public final class PublishedMessage {

    private final OutboundTopicMessage outboundMessage;
    private final long estimatedBytes;
    private final String coalesceKey;

    public PublishedMessage(OutboundTopicMessage outboundMessage, String coalesceKey) {
        this.outboundMessage = Objects.requireNonNull(outboundMessage, "outboundMessage");
        this.estimatedBytes = outboundMessage.estimatedBytes();
        if (estimatedBytes <= 0) {
            throw new IllegalArgumentException("estimatedBytes must be positive");
        }
        this.coalesceKey = coalesceKey;
    }

    public OutboundTopicMessage outboundMessage() {
        return outboundMessage;
    }

    public String messageId() {
        return outboundMessage.metadata().messageId();
    }

    public long estimatedBytes() {
        return estimatedBytes;
    }

    public String coalesceKey() {
        return coalesceKey;
    }

    public String namespace() {
        return outboundMessage.metadata().namespace();
    }

    public String topic() {
        return outboundMessage.metadata().topic();
    }

    public boolean ackRequired() {
        return outboundMessage.metadata().ackRequired();
    }
}
