package io.streamfence.internal.delivery;

public record RetryDecision(
        RetryAction action,
        String clientId,
        String namespace,
        String topic,
        LaneEntry pendingMessage
) {
}
