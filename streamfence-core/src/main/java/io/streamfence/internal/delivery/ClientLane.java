package io.streamfence.internal.delivery;

import io.streamfence.DeliveryMode;
import io.streamfence.OverflowAction;
import io.streamfence.internal.config.TopicPolicy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

public final class ClientLane {

    private final TopicPolicy topicPolicy;
    private final Deque<LaneEntry> queue = new ArrayDeque<>();
    private long queuedBytes;
    // Count of queued entries currently marked awaitingAck. Maintained
    // incrementally by {@link #markAwaiting(LaneEntry)} /
    // {@link #clearAwaiting(LaneEntry)} so drainTopic can check the in-flight
    // window in O(1) instead of walking the deque on every loop iteration.
    // All mutation happens under the lane monitor.
    private int inFlightCount;

    public ClientLane(TopicPolicy topicPolicy) {
        this.topicPolicy = Objects.requireNonNull(topicPolicy, "topicPolicy");
    }

    public synchronized EnqueueResult enqueue(LaneEntry laneEntry) {
        Objects.requireNonNull(laneEntry, "laneEntry");
        if (laneEntry.estimatedBytes() > topicPolicy.maxQueuedBytesPerClient()) {
            return new EnqueueResult(EnqueueStatus.REJECTED, "Message exceeds configured byte limit");
        }

        if (topicPolicy.overflowAction() == OverflowAction.SNAPSHOT_ONLY) {
            return replaceSnapshot(laneEntry);
        }

        if (topicPolicy.coalesce() || topicPolicy.overflowAction() == OverflowAction.COALESCE) {
            EnqueueResult coalesced = tryCoalesce(laneEntry);
            if (coalesced != null) {
                return coalesced;
            }
        }

        if (fitsAfterAdding(laneEntry)) {
            queue.addLast(laneEntry);
            queuedBytes += laneEntry.estimatedBytes();
            return new EnqueueResult(EnqueueStatus.ACCEPTED, "accepted");
        }

        return switch (topicPolicy.overflowAction()) {
            case DROP_OLDEST -> dropOldestUntilFits(laneEntry);
            case REJECT_NEW -> new EnqueueResult(EnqueueStatus.REJECTED, rejectionReason(laneEntry));
            case COALESCE -> new EnqueueResult(EnqueueStatus.REJECTED, "No coalescable message available");
            case SNAPSHOT_ONLY -> replaceSnapshot(laneEntry);
            case SPILL_TO_DISK -> new EnqueueResult(EnqueueStatus.REJECTED, "SPILL_TO_DISK is not enabled in v1");
        };
    }

    public synchronized LaneEntry peek() {
        return queue.peekFirst();
    }

    /**
     * Returns whether the lane currently contains work that can be sent.
     * Reliable lanes with only in-flight entries return false so the
     * dispatcher does not spin re-scheduling drains while waiting for ACKs.
     */
    public synchronized boolean hasPendingSend() {
        if (queue.isEmpty()) {
            return false;
        }
        if (topicPolicy.deliveryMode() != DeliveryMode.AT_LEAST_ONCE) {
            return true;
        }
        return firstPendingSend() != null;
    }

    /**
     * Returns the first entry in send order that is not yet awaiting an ACK,
     * or {@code null} if every queued entry is already in flight. Used by
     * {@code TopicDispatcher.drainTopic} to pipeline reliable sends up to the
     * topic policy's {@code maxInFlight} window.
     *
     * <p>Walking the deque is O(queue size) in the worst case but queues are
     * bounded (default 256 entries) and drain is single-threaded per topic,
     * so this is cheap in practice.
     */
    public synchronized LaneEntry firstPendingSend() {
        for (LaneEntry entry : queue) {
            if (!entry.awaitingAck()) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Returns an entry by message id regardless of its position in the queue,
     * or {@code null} when the message is no longer queued.
     */
    public synchronized LaneEntry findByMessageId(String messageId) {
        for (LaneEntry entry : queue) {
            if (entry.messageId().equals(messageId)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Returns how many entries in this lane are currently in flight
     * (awaiting ACK). Maintained incrementally via {@link #markAwaiting} /
     * {@link #clearAwaiting}, so this is O(1) - the previous implementation
     * walked the deque on every call.
     */
    public synchronized int inFlightCount() {
        return inFlightCount;
    }

    /**
     * Marks an entry awaitingAck and increments the in-flight counter.
     * Must be called by the dispatcher under the lane's happens-before
     * relationship (it is called from the single drain thread per topic).
     */
    public synchronized void markAwaiting(LaneEntry entry) {
        if (!entry.awaitingAck()) {
            entry.markAwaitingAck(true);
            inFlightCount++;
        }
    }

    /**
     * Clears awaitingAck on an entry and decrements the in-flight counter.
     * Used for ack, retry rollback, or cleanup paths that still have the
     * entry in the queue.
     */
    public synchronized void clearAwaiting(LaneEntry entry) {
        if (entry.awaitingAck()) {
            entry.markAwaitingAck(false);
            inFlightCount--;
        }
    }

    public synchronized LaneEntry poll() {
        LaneEntry laneEntry = queue.pollFirst();
        if (laneEntry != null) {
            onEntryRemoved(laneEntry);
        }
        return laneEntry;
    }

    public synchronized LaneEntry removeHeadIfMatches(String messageId) {
        LaneEntry head = queue.peekFirst();
        if (head != null && head.messageId().equals(messageId)) {
            return poll();
        }
        return null;
    }

    /**
     * Removes an entry by message id regardless of its position in the queue.
     * Used by the dispatcher to drop an entry whose reliable send failed
     * mid-pipeline (with {@code maxInFlight > 1} the failed entry may not be
     * the head). O(queue size), which is bounded by
     * {@code maxQueuedMessagesPerClient}.
     */
    public synchronized LaneEntry removeByMessageId(String messageId) {
        var iterator = queue.iterator();
        while (iterator.hasNext()) {
            LaneEntry entry = iterator.next();
            if (entry.messageId().equals(messageId)) {
                iterator.remove();
                onEntryRemoved(entry);
                return entry;
            }
        }
        return null;
    }

    private void onEntryRemoved(LaneEntry entry) {
        queuedBytes -= entry.estimatedBytes();
        if (entry.awaitingAck()) {
            entry.markAwaitingAck(false);
            inFlightCount--;
        }
    }

    public synchronized int size() {
        return queue.size();
    }

    public synchronized long queuedBytes() {
        return queuedBytes;
    }

    public synchronized List<LaneEntry> snapshot() {
        return List.copyOf(queue);
    }

    public TopicPolicy topicPolicy() {
        return topicPolicy;
    }

    private EnqueueResult replaceSnapshot(LaneEntry laneEntry) {
        boolean replaced = !queue.isEmpty();
        for (LaneEntry existing : queue) {
            if (existing.awaitingAck()) {
                existing.markAwaitingAck(false);
            }
        }
        queue.clear();
        queuedBytes = 0;
        inFlightCount = 0;
        queue.addLast(laneEntry);
        queuedBytes = laneEntry.estimatedBytes();
        return new EnqueueResult(replaced ? EnqueueStatus.REPLACED_SNAPSHOT : EnqueueStatus.ACCEPTED,
                replaced ? "snapshot replaced" : "accepted");
    }

    private EnqueueResult tryCoalesce(LaneEntry laneEntry) {
        if (laneEntry.coalesceKey() == null || laneEntry.coalesceKey().isBlank()) {
            return null;
        }

        List<LaneEntry> snapshot = new ArrayList<>(queue);
        for (int index = 0; index < snapshot.size(); index++) {
            LaneEntry existing = snapshot.get(index);
            if (laneEntry.coalesceKey().equals(existing.coalesceKey())) {
                long recalculatedBytes = queuedBytes - existing.estimatedBytes() + laneEntry.estimatedBytes();
                if (recalculatedBytes > topicPolicy.maxQueuedBytesPerClient()) {
                    return new EnqueueResult(EnqueueStatus.REJECTED, "Coalesced message exceeds byte limit");
                }
                snapshot.set(index, laneEntry);
                queue.clear();
                queue.addAll(snapshot);
                queuedBytes = recalculatedBytes;
                return new EnqueueResult(EnqueueStatus.COALESCED, "coalesced");
            }
        }
        return null;
    }

    private EnqueueResult dropOldestUntilFits(LaneEntry laneEntry) {
        boolean dropped = false;
        while (!queue.isEmpty() && !fitsAfterAdding(laneEntry)) {
            LaneEntry removed = queue.removeFirst();
            onEntryRemoved(removed);
            dropped = true;
        }

        if (!fitsAfterAdding(laneEntry)) {
            return new EnqueueResult(EnqueueStatus.REJECTED, rejectionReason(laneEntry));
        }

        queue.addLast(laneEntry);
        queuedBytes += laneEntry.estimatedBytes();
        return new EnqueueResult(
                dropped ? EnqueueStatus.DROPPED_OLDEST_AND_ACCEPTED : EnqueueStatus.ACCEPTED,
                dropped ? "Dropped oldest queued message" : "accepted"
        );
    }

    private boolean fitsAfterAdding(LaneEntry laneEntry) {
        return queue.size() + 1 <= topicPolicy.maxQueuedMessagesPerClient()
                && queuedBytes + laneEntry.estimatedBytes() <= topicPolicy.maxQueuedBytesPerClient();
    }

    private String rejectionReason(LaneEntry laneEntry) {
        if (queue.size() + 1 > topicPolicy.maxQueuedMessagesPerClient()) {
            return "Queue count limit exceeded";
        }
        if (queuedBytes + laneEntry.estimatedBytes() > topicPolicy.maxQueuedBytesPerClient()) {
            return "Queue bytes limit exceeded";
        }
        return "Queue policy rejected message";
    }
}
