package io.streamfence;

/**
 * Server-level authentication mode.
 *
 * <p>The mode controls whether connecting clients must supply a bearer token that is validated
 * by a {@link TokenValidator} before they are allowed to subscribe or publish.
 */
public enum AuthMode {

    /** No authentication is required; all connections are accepted unconditionally. */
    NONE,

    /**
     * Token-based authentication is required. Clients must send a {@code token} handshake
     * parameter which is forwarded to the configured {@link TokenValidator}.
     */
    TOKEN
}
