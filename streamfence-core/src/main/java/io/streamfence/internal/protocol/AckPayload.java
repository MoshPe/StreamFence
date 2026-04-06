package io.streamfence.internal.protocol;

public record AckPayload(
        String topic,
        String messageId
) {
}
