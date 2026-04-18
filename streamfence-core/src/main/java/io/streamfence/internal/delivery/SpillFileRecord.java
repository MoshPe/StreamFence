package io.streamfence.internal.delivery;

record SpillFileRecord(
        String eventName,
        String namespace,
        String topic,
        String messageId,
        boolean ackRequired,
        long estimatedBytes,
        Object payload
) {
}
