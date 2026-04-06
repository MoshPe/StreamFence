package io.streamfence;

/**
 * The result of an authentication or authorization check performed by a {@link TokenValidator}.
 *
 * <p>An accepted decision carries the resolved {@code principal} name. A rejected decision
 * carries a human-readable {@code reason} string that is logged and surfaced to the client as
 * an {@code error} event.
 *
 * @param accepted  {@code true} when the token was accepted; {@code false} when rejected
 * @param principal the resolved identity for accepted decisions; {@code null} for rejections
 * @param reason    a human-readable explanation; {@code "accepted"} for successful decisions
 */
public record AuthDecision(
        boolean accepted,
        String principal,
        String reason
) {

    /**
     * Creates an accepted decision with the given principal name.
     *
     * @param principal the resolved identity of the authenticated client
     * @return an accepted {@code AuthDecision}
     */
    public static AuthDecision accept(String principal) {
        return new AuthDecision(true, principal, "accepted");
    }

    /**
     * Creates a rejected decision with the given reason.
     *
     * @param reason a human-readable explanation for the rejection
     * @return a rejected {@code AuthDecision}
     */
    public static AuthDecision reject(String reason) {
        return new AuthDecision(false, null, reason);
    }
}
