package io.streamfence.internal.delivery;

public record EnqueueResult(
        EnqueueStatus status,
        String reason
) {
}
