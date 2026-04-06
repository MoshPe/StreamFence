package io.streamfence.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthRateLimiterTest {

    @Test
    void allowsUntilMaxRejectsInWindow() {
        AuthRateLimiter limiter = new AuthRateLimiter(3, 10_000);

        long now = 1_000L;
        assertThat(limiter.allow("1.2.3.4", now)).isTrue();

        assertThat(limiter.recordReject("1.2.3.4", now)).isTrue(); // first of window
        assertThat(limiter.allow("1.2.3.4", now)).isTrue();

        assertThat(limiter.recordReject("1.2.3.4", now)).isFalse();
        assertThat(limiter.allow("1.2.3.4", now)).isTrue();

        assertThat(limiter.recordReject("1.2.3.4", now)).isFalse();
        assertThat(limiter.allow("1.2.3.4", now)).isFalse();
    }

    @Test
    void resetsAfterWindowElapses() {
        AuthRateLimiter limiter = new AuthRateLimiter(2, 1_000);

        limiter.recordReject("9.9.9.9", 0L);
        limiter.recordReject("9.9.9.9", 500L);
        assertThat(limiter.allow("9.9.9.9", 500L)).isFalse();

        // Past the window -> counter resets on the next reject.
        assertThat(limiter.allow("9.9.9.9", 1_500L)).isTrue();
        assertThat(limiter.recordReject("9.9.9.9", 1_500L)).isTrue();
        assertThat(limiter.allow("9.9.9.9", 1_500L)).isTrue();
    }

    @Test
    void reapRemovesStaleEntries() {
        AuthRateLimiter limiter = new AuthRateLimiter(5, 1_000);

        limiter.recordReject("a", 0L);
        limiter.recordReject("b", 0L);
        assertThat(limiter.trackedRemotes()).isEqualTo(2);

        limiter.reap(2_000L);
        assertThat(limiter.trackedRemotes()).isZero();
    }

    @Test
    void tracksPerRemoteIndependently() {
        AuthRateLimiter limiter = new AuthRateLimiter(1, 10_000);

        limiter.recordReject("1.1.1.1", 0L);
        assertThat(limiter.allow("1.1.1.1", 0L)).isFalse();
        assertThat(limiter.allow("2.2.2.2", 0L)).isTrue();
    }
}
