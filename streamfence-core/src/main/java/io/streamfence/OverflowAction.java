package io.streamfence;

/**
 * Action taken when a client's per-topic queue is full and a new message arrives.
 *
 * <p>The overflow action is configured per namespace and applies uniformly to all topics in
 * that namespace. {@link DeliveryMode#AT_LEAST_ONCE} namespaces must use {@link #REJECT_NEW}.
 */
public enum OverflowAction {

    /**
     * Remove the oldest enqueued message and accept the new one. The dropped message is lost
     * and the client will never receive it. Useful for live-data feeds where stale values are
     * less harmful than blocking new updates.
     */
    DROP_OLDEST,

    /**
     * Reject the incoming message and leave the queue unchanged. The publisher receives a
     * {@link ServerEventListener.QueueOverflowEvent}. Suitable for reliable pipelines where
     * back-pressure should propagate to the sender.
     */
    REJECT_NEW,

    /**
     * Replace the most recent pending message of the same topic with the new one. Effective
     * for snapshot-style feeds (e.g. price tickers) where only the latest value matters.
     * Only applicable to {@link DeliveryMode#BEST_EFFORT} namespaces.
     */
    COALESCE,

    /**
     * Keep only the latest message in the queue; all older pending messages are discarded.
     * The client receives a single up-to-date snapshot on drain rather than a backlog of
     * stale entries.
     */
    SNAPSHOT_ONLY,

    /**
     * Overflow messages are spilled to a local disk buffer and replayed when the client's
     * in-memory queue drains. Suitable for bursty workloads where temporary backpressure is
     * acceptable but message loss is not.
     */
    SPILL_TO_DISK
}
