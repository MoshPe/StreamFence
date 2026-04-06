package io.streamfence.internal.delivery;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class RetryService {

    private final AckTracker ackTracker;

    public RetryService(AckTracker ackTracker) {
        this.ackTracker = Objects.requireNonNull(ackTracker, "ackTracker");
    }

    public List<RetryDecision> scan(Instant now) {
        return ackTracker.collectExpired(now);
    }
}
