package io.streamfence.internal.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.streamfence.DeliveryMode;
import io.streamfence.OverflowAction;
import java.util.List;
import java.util.stream.Stream;

/**
 * YAML input shape for a topic policy. Unlike {@link TopicPolicy} (runtime,
 * always single-topic) this record accepts multiple topic names that share
 * the same policy settings. The loader expands each definition into one
 * {@link TopicPolicy} per topic.
 *
 * <p>Two input shapes are accepted:
 * <ul>
 *   <li>{@code topic: foo} — single-topic shorthand</li>
 *   <li>{@code topics: [foo, bar]} — multi-topic list</li>
 * </ul>
 * Exactly one of the two must be provided.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TopicPolicyDefinition(
        String namespace,
        String topic,
        List<String> topics,
        DeliveryMode deliveryMode,
        OverflowAction overflowAction,
        int maxQueuedMessagesPerClient,
        long maxQueuedBytesPerClient,
        long ackTimeoutMs,
        int maxRetries,
        boolean coalesce,
        boolean allowPolling,
        boolean authRequired,
        int maxInFlight
) {
    public Stream<TopicPolicy> expand() {
        List<String> resolved = resolveTopics();
        return resolved.stream().map(topicName -> new TopicPolicy(
                namespace,
                topicName,
                deliveryMode,
                overflowAction,
                maxQueuedMessagesPerClient,
                maxQueuedBytesPerClient,
                ackTimeoutMs,
                maxRetries,
                coalesce,
                allowPolling,
                authRequired,
                maxInFlight));
    }

    private List<String> resolveTopics() {
        boolean hasSingle = topic != null && !topic.isBlank();
        boolean hasList = topics != null && !topics.isEmpty();
        if (hasSingle && hasList) {
            throw new IllegalArgumentException(
                    "Topic policy for namespace " + namespace + " must specify either 'topic' or 'topics', not both");
        }
        if (!hasSingle && !hasList) {
            throw new IllegalArgumentException(
                    "Topic policy for namespace " + namespace + " must specify 'topic' or 'topics'");
        }
        return hasSingle ? List.of(topic) : List.copyOf(topics);
    }
}
