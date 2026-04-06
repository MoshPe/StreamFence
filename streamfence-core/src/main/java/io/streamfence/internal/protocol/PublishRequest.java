package io.streamfence.internal.protocol;

import com.fasterxml.jackson.databind.JsonNode;

public record PublishRequest(
        String topic,
        JsonNode payload,
        String token
) {
}
