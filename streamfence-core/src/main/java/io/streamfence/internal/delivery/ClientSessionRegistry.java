package io.streamfence.internal.delivery;

import io.streamfence.internal.config.TopicPolicy;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the live set of connected clients and a secondary index from
 * {@code (namespace, topic)} to the subscribers of that topic.
 *
 * <p>The subscription index replaces the previous full-scan fan-out in
 * {@code TopicDispatcher.publish}. At the target load profile (250 clients,
 * ~25 k msg/s aggregate), scanning every session on every publish is the
 * hottest loop in the server; the index reduces it to an O(1) lookup plus
 * iteration over just the subscribers of that topic.
 *
 * <p>All maps are {@link ConcurrentHashMap} based and subscriber sets are
 * {@link ConcurrentHashMap#newKeySet() concurrent sets}, so subscribe,
 * unsubscribe, disconnect and publish can safely race. A subscribe that
 * happens while a publish is in flight is allowed to be missed by that
 * publish — this matches the current best-effort / at-least-once semantics.
 */
public final class ClientSessionRegistry {

    private final Map<String, ClientSessionState> sessions = new ConcurrentHashMap<>();
    private final Map<SubscriptionKey, Set<ClientSessionState>> subscribers = new ConcurrentHashMap<>();

    public void register(ClientSessionState sessionState) {
        sessions.put(sessionState.clientId(), sessionState);
    }

    public ClientSessionState get(String clientId) {
        return sessions.get(clientId);
    }

    /**
     * Removes the client and drops it from every subscription index bucket
     * it was listed in. Called from the namespace disconnect path.
     */
    public void remove(String clientId) {
        ClientSessionState removed = sessions.remove(clientId);
        if (removed == null) {
            return;
        }
        for (String topic : removed.subscriptions()) {
            removeFromIndex(removed, topic);
        }
    }

    public Collection<ClientSessionState> allSessions() {
        return sessions.values();
    }

    /**
     * Registers a subscription: updates the per-client session state and the
     * reverse index used by publish. Idempotent.
     */
    public void subscribe(ClientSessionState sessionState, String topic, TopicPolicy topicPolicy) {
        sessionState.subscribe(topic, topicPolicy);
        subscribers
                .computeIfAbsent(new SubscriptionKey(sessionState.namespace(), topic),
                        ignored -> ConcurrentHashMap.newKeySet())
                .add(sessionState);
    }

    /**
     * Removes a subscription from both the per-client session state and the
     * reverse index.
     */
    public void unsubscribe(ClientSessionState sessionState, String topic) {
        sessionState.unsubscribe(topic);
        removeFromIndex(sessionState, topic);
    }

    /**
     * Returns the subscribers of a topic for fan-out. The returned collection
     * is a live view of the underlying concurrent set and is safe to iterate
     * without external synchronization.
     */
    public Collection<ClientSessionState> subscribersOf(String namespace, String topic) {
        Set<ClientSessionState> set = subscribers.get(new SubscriptionKey(namespace, topic));
        return set == null ? Collections.emptySet() : set;
    }

    private void removeFromIndex(ClientSessionState sessionState, String topic) {
        SubscriptionKey key = new SubscriptionKey(sessionState.namespace(), topic);
        Set<ClientSessionState> set = subscribers.get(key);
        if (set == null) {
            return;
        }
        set.remove(sessionState);
        // Intentionally leave an empty bucket in place: topics are a bounded
        // configured set, so bucket churn cost is not worth the extra
        // computeIfPresent / remove-if-empty dance on the hot path.
    }

    private record SubscriptionKey(String namespace, String topic) {
    }
}
