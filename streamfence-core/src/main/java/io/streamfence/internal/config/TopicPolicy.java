package io.streamfence.internal.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.streamfence.DeliveryMode;
import io.streamfence.OverflowAction;

/**
 * Per-topic policy. {@code maxInFlight} controls how many messages can be
 * awaiting ACK at the same time on a single client lane for
 * {@link DeliveryMode#AT_LEAST_ONCE} topics. With {@code maxInFlight = 1}
 * the lane sends one message and waits for its ACK before sending the next,
 * which caps per-client reliable throughput at {@code 1 / RTT}. Raising it
 * allows a pipelined in-flight window and is required to sustain high
 * reliable throughput. For {@code BEST_EFFORT} topics the field is ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TopicPolicy(
        String namespace,
        String topic,
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
    /**
     * Canonical constructor normalizing {@code maxInFlight}. Values <= 0
     * (including the Jackson default when the field is omitted from YAML)
     * are treated as 1 so existing configs preserve their behaviour.
     */
    public TopicPolicy {
        if (maxInFlight <= 0) {
            maxInFlight = 1;
        }
    }
}
