package io.streamfence.internal.delivery;

import com.corundumstudio.socketio.SocketIOClient;
import io.streamfence.internal.config.TopicPolicy;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ClientSessionState {

    private final String clientId;
    private final String namespace;
    private final SocketIOClient client;
    private final Set<String> subscriptions = ConcurrentHashMap.newKeySet();
    private final Map<String, ClientLane> lanes = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> drainingTopics = new ConcurrentHashMap<>();

    public ClientSessionState(
            String clientId,
            String namespace,
            SocketIOClient client
    ) {
        this.clientId = clientId;
        this.namespace = namespace;
        this.client = client;
    }

    public String clientId() {
        return clientId;
    }

    public String namespace() {
        return namespace;
    }

    public SocketIOClient client() {
        return client;
    }

    public void subscribe(String topic, TopicPolicy topicPolicy) {
        subscribe(topic, topicPolicy, null);
    }

    public void subscribe(String topic, TopicPolicy topicPolicy, Path spillRoot) {
        subscriptions.add(topic);
        lanes.computeIfAbsent(topic, ignored -> new ClientLane(topicPolicy, spillRoot));
    }

    public void unsubscribe(String topic) {
        subscriptions.remove(topic);
        // Drop the lane (and any queued messages) and clear the drain flag so
        // the per-topic state does not leak across repeated subscribe cycles.
        ClientLane removed = lanes.remove(topic);
        if (removed != null) {
            removed.close();
        }
        drainingTopics.remove(topic);
    }

    public boolean isSubscribed(String topic) {
        return subscriptions.contains(topic);
    }

    /**
     * Live, unmodifiable view of the client's current topic subscriptions.
     * Used by the session registry on disconnect to know which subscription
     * index buckets the client must be removed from.
     */
    public Set<String> subscriptions() {
        return Collections.unmodifiableSet(subscriptions);
    }

    /**
     * Closes all lanes for this session, releasing any spill files on disk.
     * Called on client disconnect to ensure spill resources are reclaimed.
     */
    public void closeAllLanes() {
        for (ClientLane lane : lanes.values()) {
            lane.close();
        }
        lanes.clear();
    }

    public ClientLane lane(String topic) {
        return lanes.get(topic);
    }

    public ClientLane lane(String topic, TopicPolicy topicPolicy) {
        return lanes.computeIfAbsent(topic, ignored -> new ClientLane(topicPolicy));
    }

    public ClientLane lane(String topic, TopicPolicy topicPolicy, Path spillRoot) {
        return lanes.computeIfAbsent(topic, ignored -> new ClientLane(topicPolicy, spillRoot));
    }

    public boolean startDrain(String topic) {
        return drainingTopics.computeIfAbsent(topic, ignored -> new AtomicBoolean()).compareAndSet(false, true);
    }

    public void finishDrain(String topic) {
        // Use a plain get here rather than computeIfAbsent. A concurrent
        // unsubscribe may have already removed the drainingTopics entry; we
        // must not resurrect it with a fresh AtomicBoolean because nothing
        // would ever clean it up and subscribe/unsubscribe churn would leak.
        AtomicBoolean flag = drainingTopics.get(topic);
        if (flag != null) {
            flag.set(false);
        }
    }
}
