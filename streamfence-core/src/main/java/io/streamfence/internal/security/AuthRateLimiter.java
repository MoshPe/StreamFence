package io.streamfence.internal.security;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-remote-address sliding-window auth-failure rate limiter. Used by the
 * namespace handler to refuse connect attempts from an IP that has already
 * produced {@code maxRejectsPerWindow} auth failures inside the rolling
 * window.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Keyed by the stringified remote address; IPv6 literals are fine.</li>
 *   <li>Reset is lazy: each call normalizes the window by dropping a stale
 *       entry and resetting its counter, so no background thread is strictly
 *       required. {@link #reap(long)} is provided for periodic cleanup to
 *       prevent unbounded map growth under attack with rotating source IPs.</li>
 *   <li>{@link #firstRejectInWindow(String)} reports whether the call that
 *       produced a new window is the first one, so the caller can log once
 *       per window instead of once per rejected attempt.</li>
 * </ul>
 *
 * <p>Thread-safe: all mutation goes through atomic {@code compute} on
 * {@link ConcurrentHashMap}.
 */
public final class AuthRateLimiter {

    private final int maxRejectsPerWindow;
    private final long windowMillis;
    private final ConcurrentHashMap<String, Window> byRemote = new ConcurrentHashMap<>();

    public AuthRateLimiter(int maxRejectsPerWindow, long windowMillis) {
        if (maxRejectsPerWindow <= 0) {
            throw new IllegalArgumentException("maxRejectsPerWindow must be positive");
        }
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("windowMillis must be positive");
        }
        this.maxRejectsPerWindow = maxRejectsPerWindow;
        this.windowMillis = windowMillis;
    }

    /**
     * Returns {@code true} if the remote is allowed to proceed. Returns
     * {@code false} once the remote has hit {@code maxRejectsPerWindow}
     * rejections inside the current window. Stale windows are reset on
     * access.
     */
    public boolean allow(String remoteAddress, long nowMillis) {
        Objects.requireNonNull(remoteAddress, "remoteAddress");
        Window window = byRemote.get(remoteAddress);
        if (window == null) {
            return true;
        }
        synchronized (window) {
            if (nowMillis - window.windowStartMillis >= windowMillis) {
                return true;
            }
            return window.rejectCount < maxRejectsPerWindow;
        }
    }

    /**
     * Records an auth failure against the remote. Returns {@code true} if
     * this was the first rejection in a fresh window (so the caller can log
     * exactly once per window instead of amplifying attack noise).
     */
    public boolean recordReject(String remoteAddress, long nowMillis) {
        Objects.requireNonNull(remoteAddress, "remoteAddress");
        Window window = byRemote.computeIfAbsent(remoteAddress, ignored -> new Window(nowMillis));
        synchronized (window) {
            if (nowMillis - window.windowStartMillis >= windowMillis) {
                window.windowStartMillis = nowMillis;
                window.rejectCount = 1;
                return true;
            }
            window.rejectCount++;
            return window.rejectCount == 1;
        }
    }

    /**
     * Removes entries whose windows have expired relative to {@code nowMillis}.
     * Invoked from the scheduler to bound memory under IP rotation.
     */
    public void reap(long nowMillis) {
        Iterator<Map.Entry<String, Window>> iterator = byRemote.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Window> entry = iterator.next();
            Window window = entry.getValue();
            synchronized (window) {
                if (nowMillis - window.windowStartMillis >= windowMillis) {
                    iterator.remove();
                }
            }
        }
    }

    /**
     * Exposed for tests only — returns the number of tracked remotes.
     */
    public int trackedRemotes() {
        return byRemote.size();
    }

    private static final class Window {
        long windowStartMillis;
        int rejectCount;

        Window(long startMillis) {
            this.windowStartMillis = startMillis;
            this.rejectCount = 0;
        }
    }
}
