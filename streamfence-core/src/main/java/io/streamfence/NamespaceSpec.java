package io.streamfence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
            if (overflowAction != OverflowAction.REJECT_NEW) {
                throw new IllegalArgumentException("AT_LEAST_ONCE namespaces must use REJECT_NEW overflowAction");
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

    public static Builder builder(String path) {
        return new Builder(path);
    }

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

        public Builder authRequired(boolean authRequired) {
            this.authRequired = authRequired;
            return this;
        }

        public Builder topics(List<String> topics) {
            this.topics.clear();
            this.topics.addAll(topics);
            return this;
        }

        public Builder topic(String topic) {
            this.topics.add(topic);
            return this;
        }

        public Builder deliveryMode(DeliveryMode deliveryMode) {
            this.deliveryMode = deliveryMode;
            return this;
        }

        public Builder overflowAction(OverflowAction overflowAction) {
            this.overflowAction = overflowAction;
            return this;
        }

        public Builder maxQueuedMessagesPerClient(int maxQueuedMessagesPerClient) {
            this.maxQueuedMessagesPerClient = maxQueuedMessagesPerClient;
            return this;
        }

        public Builder maxQueuedBytesPerClient(long maxQueuedBytesPerClient) {
            this.maxQueuedBytesPerClient = maxQueuedBytesPerClient;
            return this;
        }

        public Builder ackTimeoutMs(long ackTimeoutMs) {
            this.ackTimeoutMs = ackTimeoutMs;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder coalesce(boolean coalesce) {
            this.coalesce = coalesce;
            return this;
        }

        public Builder allowPolling(boolean allowPolling) {
            this.allowPolling = allowPolling;
            return this;
        }

        public Builder maxInFlight(int maxInFlight) {
            this.maxInFlight = maxInFlight;
            return this;
        }

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
