package io.streamfence.internal.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;

public final class AckTracker {

    // Pending state keyed by (clientId, namespace, topic, messageId). The heap
    // only references entries by key + generation, so lookups during scan are
    // O(1) against this map.
    private final Map<PendingAckKey, PendingAckState> pending = new ConcurrentHashMap<>();

    // Min-heap of expiration handles ordered by deadline. Stale entries (after
    // acknowledge, retry, or client/topic removal) are left in the heap and
    // skipped on scan — O(log n) insert per register/retry instead of the old
    // O(n) full-map scan per tick. At 250 clients × 100 msg/s with
    // maxInFlight=8 the in-flight set can grow to ~2000 entries and the old
    // scan was walking all of them every tick.
    private final PriorityBlockingQueue<ExpirationHandle> expirationHeap =
            new PriorityBlockingQueue<>(256, Comparator.comparing(ExpirationHandle::deadline));

    public void register(
            String clientId,
            String namespace,
            String topic,
            LaneEntry pendingMessage,
            Duration ackTimeout,
            int maxRetries,
            Instant now
    ) {
        Objects.requireNonNull(pendingMessage, "pendingMessage");
        Objects.requireNonNull(ackTimeout, "ackTimeout");
        Objects.requireNonNull(now, "now");
        PendingAckKey key = new PendingAckKey(clientId, namespace, topic, pendingMessage.messageId());
        Instant deadline = now.plus(ackTimeout);
        // Ownership of the {@code awaitingAck} flag belongs to ClientLane so
        // the per-lane inFlightCount stays consistent. The dispatcher calls
        // {@code lane.markAwaiting} before {@code register}, and entries are
        // cleared when they leave the lane via poll/remove.
        PendingAckState state = new PendingAckState(key, pendingMessage, ackTimeout, maxRetries, deadline);
        pending.put(key, state);
        expirationHeap.offer(new ExpirationHandle(deadline, key, state.generation()));
    }

    public boolean acknowledge(String clientId, String namespace, String topic, String messageId) {
        PendingAckState removed = pending.remove(new PendingAckKey(clientId, namespace, topic, messageId));
        // Stale heap entry is discarded on the next scan. The lane clears
        // awaitingAck + the in-flight counter when the entry is polled.
        return removed != null;
    }

    public List<RetryDecision> collectExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        List<RetryDecision> decisions = new ArrayList<>();
        while (true) {
            ExpirationHandle head = expirationHeap.peek();
            if (head == null || head.deadline().isAfter(now)) {
                return decisions;
            }
            // Pop the head we just peeked. Because collectExpired is called
            // from a single scheduler thread this is the same handle; if a
            // concurrent retry slipped something earlier in between, we'd
            // simply re-evaluate on the next iteration.
            ExpirationHandle expired = expirationHeap.poll();
            if (expired == null) {
                return decisions;
            }

            PendingAckState state = pending.get(expired.key());
            if (state == null) {
                // Acknowledged or removed via client/topic cleanup — drop.
                continue;
            }
            if (state.generation() != expired.generation()) {
                // Superseded by a later retry/register that pushed a newer
                // handle with a fresh generation. Drop this stale one.
                continue;
            }

            if (state.pendingMessage().retryCount() < state.maxRetries()) {
                state.pendingMessage().incrementRetryCount();
                Instant nextDeadline = now.plus(state.ackTimeout());
                int nextGeneration = state.bumpGeneration(nextDeadline);
                expirationHeap.offer(new ExpirationHandle(nextDeadline, state.key(), nextGeneration));
                decisions.add(new RetryDecision(
                        RetryAction.RETRY,
                        state.key().clientId(),
                        state.key().namespace(),
                        state.key().topic(),
                        state.pendingMessage()
                ));
            } else if (pending.remove(state.key(), state)) {
                decisions.add(new RetryDecision(
                        RetryAction.EXHAUSTED,
                        state.key().clientId(),
                        state.key().namespace(),
                        state.key().topic(),
                        state.pendingMessage()
                ));
            }
        }
    }

    public int pendingCount() {
        return pending.size();
    }

    /**
     * Drops all pending entries for a disconnected client. Called from the
     * namespace disconnect path so the pending map does not accumulate state
     * that will only be cleaned up when the retry budget is exhausted. Stale
     * heap handles for the removed keys are dropped on the next scan.
     */
    public void removeClient(String clientId) {
        Objects.requireNonNull(clientId, "clientId");
        pending.entrySet().removeIf(entry -> entry.getKey().clientId().equals(clientId));
        // The client's lanes are discarded by the session registry on
        // disconnect, so the in-flight counters go away with them.
    }

    /**
     * Drops pending entries for a single (client, namespace, topic) tuple.
     * Used when a client unsubscribes from a topic so we do not keep retrying
     * messages the client can no longer receive.
     */
    public void removeClientTopic(String clientId, String namespace, String topic) {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(topic, "topic");
        pending.entrySet().removeIf(entry -> {
            PendingAckKey key = entry.getKey();
            return key.clientId().equals(clientId)
                    && key.namespace().equals(namespace)
                    && key.topic().equals(topic);
        });
        // Lane is dropped by ClientSessionState.unsubscribe on the same path.
    }

    private record PendingAckKey(
            String clientId,
            String namespace,
            String topic,
            String messageId
    ) {
    }

    private record ExpirationHandle(Instant deadline, PendingAckKey key, int generation) {
    }

    private static final class PendingAckState {
        private final PendingAckKey key;
        private final LaneEntry pendingMessage;
        private final Duration ackTimeout;
        private final int maxRetries;
        private volatile Instant nextDeadline;
        // Incremented each time a new heap handle is published for this entry
        // (initial register = 0, each retry = +1). The scan compares this to
        // the handle's generation to detect stale entries.
        private volatile int generation;

        private PendingAckState(
                PendingAckKey key,
                LaneEntry pendingMessage,
                Duration ackTimeout,
                int maxRetries,
                Instant nextDeadline
        ) {
            this.key = key;
            this.pendingMessage = pendingMessage;
            this.ackTimeout = ackTimeout;
            this.maxRetries = maxRetries;
            this.nextDeadline = nextDeadline;
            this.generation = 0;
        }

        PendingAckKey key() {
            return key;
        }

        LaneEntry pendingMessage() {
            return pendingMessage;
        }

        Duration ackTimeout() {
            return ackTimeout;
        }

        int maxRetries() {
            return maxRetries;
        }

        Instant nextDeadline() {
            return nextDeadline;
        }

        int generation() {
            return generation;
        }

        /**
         * Advances the deadline and generation in a single step. Only the
         * scan thread calls this, so a plain write is sufficient under the
         * single-writer invariant for PendingAckState mutations.
         */
        int bumpGeneration(Instant nextDeadline) {
            this.nextDeadline = nextDeadline;
            this.generation = this.generation + 1;
            return this.generation;
        }
    }
}
