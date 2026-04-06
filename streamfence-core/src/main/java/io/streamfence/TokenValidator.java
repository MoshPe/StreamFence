package io.streamfence;

/**
 * Strategy interface for token-based client authentication.
 *
 * <p>Implement this interface and register it via
 * {@link SocketIoServerBuilder#tokenValidator(TokenValidator)} to perform custom auth logic.
 * The server invokes {@link #validate} during the Socket.IO handshake for every namespace
 * configured with {@link AuthMode#TOKEN}.
 *
 * <p>Implementations must be thread-safe. Exceptions thrown from {@link #validate} are caught
 * and treated as a rejection.
 */
public interface TokenValidator {

    /**
     * Validates a bearer token for a connecting client.
     *
     * @param token     the raw token string supplied by the client; never {@code null}
     * @param namespace the namespace path the client is connecting to
     * @param topic     the topic the client is attempting to access, or {@code null} if
     *                  the check is at connection time rather than subscription time
     * @return an {@link AuthDecision} indicating acceptance or rejection; must not be
     *         {@code null}
     */
    AuthDecision validate(String token, String namespace, String topic);
}
