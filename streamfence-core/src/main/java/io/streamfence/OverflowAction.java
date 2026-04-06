package io.streamfence;

public enum OverflowAction {
    DROP_OLDEST,
    REJECT_NEW,
    COALESCE,
    SNAPSHOT_ONLY,
    SPILL_TO_DISK
}
