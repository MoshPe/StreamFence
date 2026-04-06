package io.streamfence.internal.protocol;

public record SubscriptionRequest(
        String topic,
        String token
) {
}
