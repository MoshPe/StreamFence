package io.streamfence.internal.security;

import io.streamfence.AuthDecision;
import io.streamfence.TokenValidator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;

public final class StaticTokenValidator implements TokenValidator {

    private final Map<String, byte[]> tokenBytesByPrincipal;

    public StaticTokenValidator(Map<String, String> staticTokens) {
        Objects.requireNonNull(staticTokens, "staticTokens");
        // Pre-encode each configured token to bytes so we never re-encode on
        // the hot path and so the comparison stays constant-time.
        this.tokenBytesByPrincipal = Map.copyOf(
                staticTokens.entrySet().stream().collect(
                        java.util.stream.Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().getBytes(StandardCharsets.UTF_8)
                        )
                )
        );
    }

    @Override
    public AuthDecision validate(String token, String namespace, String topic) {
        if (token == null || token.isBlank()) {
            return AuthDecision.reject("Missing token");
        }

        // Constant-time comparison against every configured token. We iterate
        // the full map rather than short-circuiting on the first match so that
        // timing does not reveal which principal's token was tried. The total
        // number of configured tokens is small (one per principal) and the
        // comparison is O(tokenLength) per entry.
        byte[] candidate = token.getBytes(StandardCharsets.UTF_8);
        String matchedPrincipal = null;
        for (Map.Entry<String, byte[]> entry : tokenBytesByPrincipal.entrySet()) {
            if (MessageDigest.isEqual(candidate, entry.getValue())) {
                matchedPrincipal = entry.getKey();
                // Do not break — keep scanning so total work is independent of
                // which (if any) entry matched.
            }
        }
        return matchedPrincipal != null
                ? AuthDecision.accept(matchedPrincipal)
                : AuthDecision.reject("Invalid token");
    }
}
