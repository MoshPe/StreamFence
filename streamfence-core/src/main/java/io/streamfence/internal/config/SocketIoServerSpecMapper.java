package io.streamfence.internal.config;

import io.streamfence.DeliveryMode;
import io.streamfence.OverflowAction;
import io.streamfence.NamespaceSpec;
import io.streamfence.SocketIoServerSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class SocketIoServerSpecMapper {

    private SocketIoServerSpecMapper() {
    }

    public static SocketIoServerSpec fromServerConfig(ServerConfig config) {
        Map<String, List<TopicPolicy>> policiesByNamespace = config.topicPolicies().stream()
                .collect(Collectors.groupingBy(TopicPolicy::namespace, LinkedHashMap::new, Collectors.toList()));

        List<NamespaceSpec> namespaces = config.namespaces().entrySet().stream()
                .map(entry -> toNamespaceSpec(entry.getKey(), entry.getValue(), policiesByNamespace.get(entry.getKey())))
                .toList();
        return new SocketIoServerSpec(
                config.host(),
                config.port(),
                config.transportMode(),
                config.tls(),
                config.pingIntervalMs(),
                config.pingTimeoutMs(),
                config.maxFramePayloadLength(),
                config.maxHttpContentLength(),
                config.compressionEnabled(),
                config.enableCors(),
                config.origin(),
                config.authMode(),
                config.staticTokens(),
                namespaces,
                config.managementHost(),
                config.managementPort(),
                config.shutdownDrainMs(),
                config.senderThreads(),
                config.authRejectWindowMs(),
                config.authRejectMaxPerWindow(),
                config.spillRootPath(),
                null,
                List.of()
        );
    }

    public static ServerConfig toServerConfig(SocketIoServerSpec spec) {
        Map<String, NamespaceConfig> namespaces = new LinkedHashMap<>();
        List<TopicPolicy> topicPolicies = new ArrayList<>();
        for (NamespaceSpec namespace : spec.namespaces()) {
            namespaces.put(namespace.path(), new NamespaceConfig(namespace.authRequired()));
            for (String topic : namespace.topics()) {
                topicPolicies.add(new TopicPolicy(
                        namespace.path(),
                        topic,
                        namespace.deliveryMode(),
                        namespace.overflowAction(),
                        namespace.maxQueuedMessagesPerClient(),
                        namespace.maxQueuedBytesPerClient(),
                        namespace.ackTimeoutMs(),
                        namespace.maxRetries(),
                        namespace.coalesce(),
                        namespace.allowPolling(),
                        namespace.authRequired(),
                        namespace.maxInFlight()
                ));
            }
        }
        return new ServerConfig(
                spec.host(),
                spec.port(),
                spec.transportMode(),
                spec.tls(),
                spec.pingIntervalMs(),
                spec.pingTimeoutMs(),
                spec.maxFramePayloadLength(),
                spec.maxHttpContentLength(),
                spec.compressionEnabled(),
                spec.enableCors(),
                spec.origin(),
                spec.authMode(),
                spec.staticTokens(),
                namespaces,
                topicPolicies,
                spec.managementHost(),
                spec.managementPort(),
                spec.shutdownDrainMs(),
                spec.senderThreads(),
                spec.authRejectWindowMs(),
                spec.authRejectMaxPerWindow(),
                spec.spillRootPath()
        );
    }

    private static NamespaceSpec toNamespaceSpec(String namespace, NamespaceConfig namespaceConfig, List<TopicPolicy> policies) {
        if (policies == null || policies.isEmpty()) {
            throw new IllegalArgumentException("namespace has no topic policies: " + namespace);
        }

        TopicPolicy first = policies.get(0);
        require(namespaceConfig.authRequired() == first.authRequired(),
                "namespace has mismatched authRequired between namespace and topic policies: " + namespace);

        NamespacePolicyShape expected = NamespacePolicyShape.from(first);
        for (TopicPolicy policy : policies) {
            require(policy.authRequired() == namespaceConfig.authRequired(),
                    "namespace has mismatched authRequired between namespace and topic policies: " + namespace);
            require(expected.equals(NamespacePolicyShape.from(policy)),
                    "namespace has mixed topic policy settings: " + namespace);
        }

        List<String> topics = policies.stream()
                .map(TopicPolicy::topic)
                .toList();

        return new NamespaceSpec(
                namespace,
                namespaceConfig.authRequired(),
                topics,
                expected.deliveryMode,
                expected.overflowAction,
                expected.maxQueuedMessagesPerClient,
                expected.maxQueuedBytesPerClient,
                expected.ackTimeoutMs,
                expected.maxRetries,
                expected.coalesce,
                expected.allowPolling,
                expected.maxInFlight
        );
    }

    private static void require(boolean expression, String message) {
        if (!expression) {
            throw new IllegalArgumentException(message);
        }
    }

    private static final class NamespacePolicyShape {
        private final DeliveryMode deliveryMode;
        private final OverflowAction overflowAction;
        private final int maxQueuedMessagesPerClient;
        private final long maxQueuedBytesPerClient;
        private final long ackTimeoutMs;
        private final int maxRetries;
        private final boolean coalesce;
        private final boolean allowPolling;
        private final int maxInFlight;

        private NamespacePolicyShape(
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
            this.deliveryMode = deliveryMode;
            this.overflowAction = overflowAction;
            this.maxQueuedMessagesPerClient = maxQueuedMessagesPerClient;
            this.maxQueuedBytesPerClient = maxQueuedBytesPerClient;
            this.ackTimeoutMs = ackTimeoutMs;
            this.maxRetries = maxRetries;
            this.coalesce = coalesce;
            this.allowPolling = allowPolling;
            this.maxInFlight = maxInFlight;
        }

        private static NamespacePolicyShape from(TopicPolicy policy) {
            return new NamespacePolicyShape(
                    policy.deliveryMode(),
                    policy.overflowAction(),
                    policy.maxQueuedMessagesPerClient(),
                    policy.maxQueuedBytesPerClient(),
                    policy.ackTimeoutMs(),
                    policy.maxRetries(),
                    policy.coalesce(),
                    policy.allowPolling(),
                    policy.maxInFlight()
            );
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof NamespacePolicyShape that)) {
                return false;
            }
            return maxQueuedMessagesPerClient == that.maxQueuedMessagesPerClient
                    && maxQueuedBytesPerClient == that.maxQueuedBytesPerClient
                    && ackTimeoutMs == that.ackTimeoutMs
                    && maxRetries == that.maxRetries
                    && coalesce == that.coalesce
                    && allowPolling == that.allowPolling
                    && maxInFlight == that.maxInFlight
                    && deliveryMode == that.deliveryMode
                    && overflowAction == that.overflowAction;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
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
