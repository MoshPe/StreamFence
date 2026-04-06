package io.streamfence.internal.delivery;

import io.streamfence.internal.config.ServerConfig;
import io.streamfence.internal.config.TopicPolicy;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class TopicRegistry {

    private final Map<String, Map<String, TopicPolicy>> policiesByNamespace;
    private final List<TopicPolicy> allPolicies;

    public TopicRegistry(ServerConfig serverConfig) {
        this.policiesByNamespace = serverConfig.topicPolicies().stream()
                .collect(Collectors.groupingBy(
                        TopicPolicy::namespace,
                        Collectors.toUnmodifiableMap(TopicPolicy::topic, topicPolicy -> topicPolicy)
                ));
        this.allPolicies = List.copyOf(serverConfig.topicPolicies());
    }

    public Optional<TopicPolicy> find(String namespace, String topic) {
        return Optional.ofNullable(policiesByNamespace.getOrDefault(namespace, Map.of()).get(topic));
    }

    public Collection<TopicPolicy> allPolicies() {
        return allPolicies;
    }
}
