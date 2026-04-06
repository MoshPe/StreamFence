package io.streamfence.internal.delivery;

public enum EnqueueStatus {
    ACCEPTED,
    REJECTED,
    DROPPED_OLDEST_AND_ACCEPTED,
    COALESCED,
    REPLACED_SNAPSHOT
}
