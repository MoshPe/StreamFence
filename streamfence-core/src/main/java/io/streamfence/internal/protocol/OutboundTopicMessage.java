package io.streamfence.internal.protocol;

import java.util.Objects;

public record OutboundTopicMessage(
        String eventName,
        TopicMessageMetadata metadata,
        Object[] eventArguments,
        long estimatedBytes
) {

    public OutboundTopicMessage {
        Objects.requireNonNull(eventName, "eventName");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(eventArguments, "eventArguments");
        if (estimatedBytes <= 0) {
            throw new IllegalArgumentException("estimatedBytes must be positive");
        }
        eventArguments = eventArguments.clone();
    }

    @Override
    public Object[] eventArguments() {
        return eventArguments.clone();
    }
}
