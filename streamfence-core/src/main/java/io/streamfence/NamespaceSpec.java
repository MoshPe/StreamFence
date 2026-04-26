package io.streamfence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable specification for a single Socket.IO namespace.
 *
 * <p>A namespace groups one or more topics under a shared delivery policy. Create instances
 * via {@link #builder(String)}.
 *
 * @param path                        the namespace path, must start with {@code "/"}
 * @param authRequired                whether connecting clients must be authenticated
 * @param topics                      the ordered list of topic names managed by this namespace
 * @param deliveryMode                the delivery guarantee applied to all topics
 * @param overflowAction              the action taken when a client queue is full
 * @param maxQueuedMessagesPerClient  maximum number of messages buffered per client per topic
 * @param maxQueuedBytesPerClient     maximum byte budget buffered per client per topic
 * @param ackTimeoutMs                time (ms) before an unacknowledged message is retried
 * @param maxRetries                  maximum retry attempts for {@link DeliveryMode#AT_LEAST_ONCE} messages
 * @param coalesce                    when {@code true}, new messages replace the most recent pending one
 * @param allowPolling                whether HTTP long-polling is permitted alongside WebSocket
 * @param maxInFlight                 maximum unacknowledged messages allowed at once per client
 */
public record NamespaceSpec(
        String path,
        boolean authRequired,
        List<String> topics,
        DeliveryMode deliveryMode,
        OverflowAction overflowAction,
        int maxQueuedMessagesPerClient,
        long maxQueuedBytesPerClient,
        long ackTimeoutMs,
        int maxRetries,
        boolean coalesce,
        boolean allowPolling,
        int maxInFlight
) {

    /**
     * Compact constructor that validates and normalises the field values.
     *
     * @throws IllegalArgumentException if any field value is invalid or if the combination
     *                                  of fields is incompatible (e.g. {@link DeliveryMode#AT_LEAST_ONCE}
     *                                  with an overflow action other than {@link OverflowAction#REJECT_NEW})
     */
    public NamespaceSpec {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            throw new IllegalArgumentException("namespace path must start with '/'");
        }
        topics = List.copyOf(topics == null ? List.of() : topics);
        if (topics.isEmpty()) {
            throw new IllegalArgumentException("namespace must define at least one topic");
        }
        Set<String> uniqueTopics = new HashSet<>();
        for (String topic : topics) {
            if (topic == null || topic.isBlank()) {
                throw new IllegalArgumentException("topic names must not be blank in namespace " + path);
            }
            if (!uniqueTopics.add(topic)) {
                throw new IllegalArgumentException("duplicate topic in namespace " + path + ": " + topic);
            }
        }
        if (deliveryMode == null) {
            throw new IllegalArgumentException("deliveryMode is required");
        }
        if (overflowAction == null) {
            throw new IllegalArgumentException("overflowAction is required");
        }
        if (maxQueuedMessagesPerClient <= 0) {
            throw new IllegalArgumentException("maxQueuedMessagesPerClient must be positive");
        }
        if (maxQueuedBytesPerClient <= 0) {
            throw new IllegalArgumentException("maxQueuedBytesPerClient must be positive");
        }
        if (ackTimeoutMs <= 0) {
            throw new IllegalArgumentException("ackTimeoutMs must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be zero or positive");
        }
        if (maxInFlight <= 0) {
            maxInFlight = 1;
        }
        if (deliveryMode == DeliveryMode.AT_LEAST_ONCE) {
            if (overflowAction != OverflowAction.REJECT_NEW && overflowAction != OverflowAction.SPILL_TO_DISK) {
                throw new IllegalArgumentException(
                        "AT_LEAST_ONCE namespaces must use REJECT_NEW or SPILL_TO_DISK overflowAction");
            }
            if (coalesce) {
                throw new IllegalArgumentException("AT_LEAST_ONCE namespaces cannot enable coalescing");
            }
            if (maxRetries <= 0) {
                throw new IllegalArgumentException("AT_LEAST_ONCE namespaces must allow at least one retry");
            }
            if (maxInFlight > maxQueuedMessagesPerClient) {
                throw new IllegalArgumentException("maxInFlight must not exceed maxQueuedMessagesPerClient");
            }
        }
    }

    /**
     * Returns a new {@link Builder} for the given namespace path.
     *
     * @param path the namespace path (e.g. {@code "/feed"}); must start with {@code "/"}
     * @return a new builder seeded with sensible defaults
     */
    public static Builder builder(String path) {
        return new Builder(path);
    }

    /**
     * Fluent builder for {@link NamespaceSpec}.
     *
     * <p>Default values: {@link DeliveryMode#BEST_EFFORT}, {@link OverflowAction#REJECT_NEW},
     * 64 messages / 512 KiB per client, 1 s ack timeout, 0 retries, polling allowed,
     * {@code maxInFlight = 1}.
     */
    public static final class Builder {
        private final String path;
        private boolean authRequired;
        private final List<String> topics = new ArrayList<>();
        private DeliveryMode deliveryMode = DeliveryMode.BEST_EFFORT;
        private OverflowAction overflowAction = OverflowAction.REJECT_NEW;
        private int maxQueuedMessagesPerClient = 64;
        private long maxQueuedBytesPerClient = 524_288;
        private long ackTimeoutMs = 1_000;
        private int maxRetries;
        private boolean coalesce;
        private boolean allowPolling = true;
        private int maxInFlight = 1;

        private Builder(String path) {
            this.path = path;
        }

        /**
         * Sets whether clients connecting to this namespace must supply a valid token.
         *
         * @param authRequired {@code true} to require authentication
         * @return this builder
         */
        public Builder authRequired(boolean authRequired) {
            this.authRequired = authRequired;
            return this;
        }

        /**
         * Replaces the topic list with the given topics.
         *
         * @param topics the topic names; must not be empty or contain blanks
         * @return this builder
         */
        public Builder topics(List<String> topics) {
            this.topics.clear();
            this.topics.addAll(topics);
            return this;
        }

        /**
         * Appends a single topic to the topic list.
         *
         * @param topic the topic name; must not be blank
         * @return this builder
         */
        public Builder topic(String topic) {
            this.topics.add(topic);
            return this;
        }

        /**
         * Sets the delivery mode for all topics in this namespace.
         *
         * @param deliveryMode the delivery guarantee; must not be {@code null}
         * @return this builder
         */
        public Builder deliveryMode(DeliveryMode deliveryMode) {
            this.deliveryMode = deliveryMode;
            return this;
        }

        /**
         * Sets the overflow action applied when a client's per-topic queue is full.
         *
         * @param overflowAction the action to take; must not be {@code null}
         * @return this builder
         */
        public Builder overflowAction(OverflowAction overflowAction) {
            this.overflowAction = overflowAction;
            return this;
        }

        /**
         * Sets the maximum number of messages buffered per client per topic.
         *
         * @param maxQueuedMessagesPerClient must be positive
         * @return this builder
         */
        public Builder maxQueuedMessagesPerClient(int maxQueuedMessagesPerClient) {
            this.maxQueuedMessagesPerClient = maxQueuedMessagesPerClient;
            return this;
        }

        /**
         * Sets the maximum byte budget buffered per client per topic.
         *
         * @param maxQueuedBytesPerClient must be positive
         * @return this builder
         */
        public Builder maxQueuedBytesPerClient(long maxQueuedBytesPerClient) {
            this.maxQueuedBytesPerClient = maxQueuedBytesPerClient;
            return this;
        }

        /**
         * Sets the time (in milliseconds) to wait for a client acknowledgement before
         * retrying an {@link DeliveryMode#AT_LEAST_ONCE} message.
         *
         * @param ackTimeoutMs must be positive
         * @return this builder
         */
        public Builder ackTimeoutMs(long ackTimeoutMs) {
            this.ackTimeoutMs = ackTimeoutMs;
            return this;
        }

        /**
         * Sets the maximum number of retry attempts for unacknowledged messages.
         *
         * @param maxRetries zero disables retries; must be non-negative
         * @return this builder
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Enables or disables message coalescing. When {@code true}, a new message for a
         * topic replaces the most recent pending message for the same client instead of
         * appending. Incompatible with {@link DeliveryMode#AT_LEAST_ONCE}.
         *
         * @param coalesce {@code true} to enable coalescing
         * @return this builder
         */
        public Builder coalesce(boolean coalesce) {
            this.coalesce = coalesce;
            return this;
        }

        /**
         * Sets whether HTTP long-polling is permitted alongside WebSocket transport.
         *
         * @param allowPolling {@code true} to permit polling
         * @return this builder
         */
        public Builder allowPolling(boolean allowPolling) {
            this.allowPolling = allowPolling;
            return this;
        }

        /**
         * Sets the maximum number of messages that can be in-flight (sent but not yet
         * acknowledged) per client for {@link DeliveryMode#AT_LEAST_ONCE} topics.
         *
         * @param maxInFlight must be positive and not exceed {@code maxQueuedMessagesPerClient}
         * @return this builder
         */
        public Builder maxInFlight(int maxInFlight) {
            this.maxInFlight = maxInFlight;
            return this;
        }

        /**
         * Builds and validates the {@link NamespaceSpec}.
         *
         * @return the validated {@code NamespaceSpec}
         * @throws IllegalArgumentException if any field value is invalid
         */
        public NamespaceSpec build() {
            return new NamespaceSpec(
                    path,
                    authRequired,
                    topics,
                    deliveryMode,
                    overflowAction,
                    maxQueuedMessagesPerClient,
                    maxQueuedBytesPerClient,
                    ackTimeoutMs,
                    maxRetries,
                    coalesce,
                    allowPolling,
                    maxInFlight
            );
        }
    }
}
