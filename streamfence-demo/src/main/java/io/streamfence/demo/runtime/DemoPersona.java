package io.streamfence.demo.runtime;

import java.time.Duration;
import java.util.List;

public record DemoPersona(
        String name,
        String namespace,
        List<String> topics,
        boolean tokenRequired,
        Duration publishCadence,
        DemoPayloadKind payloadKind,
        int connectionCount,
        Duration ackDelay
) {
    public DemoPersona {
        topics = List.copyOf(topics);
        if (connectionCount <= 0) {
            throw new IllegalArgumentException("connectionCount must be positive");
        }
        if (ackDelay == null || ackDelay.isNegative()) {
            throw new IllegalArgumentException("ackDelay must be zero or positive");
        }
    }
}
