package io.streamfence.internal.protocol;

public record TopicMessageEnvelope(
        TopicMessageMetadata metadata,
        Object payload
) {
}
