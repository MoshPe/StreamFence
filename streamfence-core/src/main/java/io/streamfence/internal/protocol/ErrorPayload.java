package io.streamfence.internal.protocol;

public record ErrorPayload(
        String code,
        String message
) {
}
