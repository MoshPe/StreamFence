package io.streamfence.internal.delivery;

public enum EnqueueStatus {
    ACCEPTED,
    REJECTED,
    DROPPED_OLDEST_AND_ACCEPTED,
    COALESCED,
    REPLACED_SNAPSHOT,
    /** Message accepted but written to disk because the in-memory queue was full. */
    SPILLED
}
