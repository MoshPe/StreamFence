package io.streamfence;

public record AuthDecision(
        boolean accepted,
        String principal,
        String reason
) {

    public static AuthDecision accept(String principal) {
        return new AuthDecision(true, principal, "accepted");
    }

    public static AuthDecision reject(String reason) {
        return new AuthDecision(false, null, reason);
    }
}
