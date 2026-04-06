package io.streamfence;

/**
 * Per-topic message delivery guarantee.
 *
 * <p>The delivery mode is configured on a {@link NamespaceSpec} and governs how the server
 * handles unacknowledged messages for each subscriber.
 */
public enum DeliveryMode {

    /**
     * Messages are delivered at most once with no acknowledgement or retry. The server
     * enqueues the message and discards it according to the configured {@link OverflowAction}
     * when the queue is full. Suitable for high-frequency, low-latency feeds where occasional
     * loss is acceptable.
     */
    BEST_EFFORT,

    /**
     * Messages are delivered at least once. Each outbound message is assigned a
     * {@code messageId} and the server retries delivery until the client sends an {@code ack}
     * or the retry budget configured on the namespace is exhausted.
     */
    AT_LEAST_ONCE
}
