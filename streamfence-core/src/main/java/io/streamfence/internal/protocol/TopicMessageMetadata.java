package io.streamfence.internal.protocol;

public record TopicMessageMetadata(
        String namespace,
        String topic,
        String messageId,
        boolean ackRequired
) {
}
