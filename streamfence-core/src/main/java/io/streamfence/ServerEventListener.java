package io.streamfence;

/**
 * Callback interface for server lifecycle and runtime events.
 *
 * <p>Implement this interface and register it via
 * {@link SocketIoServerBuilder#listener(ServerEventListener)} to observe events without
 * coupling to internal classes. All methods have empty default implementations so implementors
 * only override what they need.
 *
 * <p>Listener failures are isolated: an exception thrown from any callback is caught and
 * logged but does not affect the server runtime or other listeners.
 */
public interface ServerEventListener {

    /**
     * Invoked immediately before the Socket.IO server binds its port.
     *
     * @param event details about the server that is about to start
     */
    default void onServerStarting(ServerStartingEvent event) {
    }

    /**
     * Invoked after the Socket.IO server has bound its port and is ready to accept
     * connections.
     *
     * @param event details about the started server
     */
    default void onServerStarted(ServerStartedEvent event) {
    }

    /**
     * Invoked when {@link SocketIoServer#stop()} or {@link SocketIoServer#close()} is called,
     * before connections are drained.
     *
     * @param event details about the server that is stopping
     */
    default void onServerStopping(ServerStoppingEvent event) {
    }

    /**
     * Invoked after the server has fully stopped and all resources have been released.
     *
     * @param event details about the stopped server
     */
    default void onServerStopped(ServerStoppedEvent event) {
    }

    /**
     * Invoked when a client establishes a Socket.IO connection to a namespace.
     *
     * @param event details about the connected client
     */
    default void onClientConnected(ClientConnectedEvent event) {
    }

    /**
     * Invoked when a client disconnects from a namespace.
     *
     * @param event details about the disconnected client
     */
    default void onClientDisconnected(ClientDisconnectedEvent event) {
    }

    /**
     * Invoked when a client successfully subscribes to a topic.
     *
     * @param event details about the subscription
     */
    default void onSubscribed(SubscribedEvent event) {
    }

    /**
     * Invoked when a client unsubscribes from a topic.
     *
     * @param event details about the unsubscription
     */
    default void onUnsubscribed(UnsubscribedEvent event) {
    }

    /**
     * Invoked when a publish call is accepted and the message is enqueued for delivery.
     *
     * @param event details about the accepted publish
     */
    default void onPublishAccepted(PublishAcceptedEvent event) {
    }

    /**
     * Invoked when a publish call is rejected because the queue is full and the overflow
     * action is {@link OverflowAction#REJECT_NEW}.
     *
     * @param event details about the rejected publish
     */
    default void onPublishRejected(PublishRejectedEvent event) {
    }

    /**
     * Invoked when a client's per-topic queue overflows and the configured
     * {@link OverflowAction} is applied.
     *
     * @param event details about the overflow
     */
    default void onQueueOverflow(QueueOverflowEvent event) {
    }

    /**
     * Invoked when a connection attempt is rejected by the {@link TokenValidator}.
     *
     * @param event details about the rejected authentication attempt
     */
    default void onAuthRejected(AuthRejectedEvent event) {
    }

    /**
     * Invoked each time an {@link DeliveryMode#AT_LEAST_ONCE} message is retried because
     * the client did not acknowledge it within the configured ack timeout.
     *
     * @param event details about the retry attempt
     */
    default void onRetry(RetryEvent event) {
    }

    /**
     * Invoked when the retry budget for an {@link DeliveryMode#AT_LEAST_ONCE} message is
     * exhausted without receiving an acknowledgement. The message is discarded.
     *
     * @param event details about the exhausted retry
     */
    default void onRetryExhausted(RetryExhaustedEvent event) {
    }

    /**
     * Fired immediately before the Socket.IO server binds its port.
     *
     * @param host             the bind address
     * @param port             the Socket.IO port
     * @param managementPort   the Prometheus scrape port, or {@code -1} if disabled
     */
    record ServerStartingEvent(String host, int port, int managementPort) {
    }

    /**
     * Fired after the Socket.IO server has successfully started.
     *
     * @param host             the bind address
     * @param port             the Socket.IO port
     * @param managementPort   the Prometheus scrape port, or {@code -1} if disabled
     */
    record ServerStartedEvent(String host, int port, int managementPort) {
    }

    /**
     * Fired when server shutdown begins.
     *
     * @param host             the bind address
     * @param port             the Socket.IO port
     * @param managementPort   the Prometheus scrape port, or {@code -1} if disabled
     */
    record ServerStoppingEvent(String host, int port, int managementPort) {
    }

    /**
     * Fired after the server has fully stopped.
     *
     * @param host             the bind address
     * @param port             the Socket.IO port
     * @param managementPort   the Prometheus scrape port, or {@code -1} if disabled
     */
    record ServerStoppedEvent(String host, int port, int managementPort) {
    }

    /**
     * Fired when a client opens a Socket.IO connection to a namespace.
     *
     * @param namespace     the namespace path (e.g. {@code "/feed"})
     * @param clientId      the server-assigned session ID
     * @param transport     the transport used ({@code "websocket"} or {@code "polling"})
     * @param principal     the authenticated principal, or {@code null} when auth is disabled
     */
    record ClientConnectedEvent(String namespace, String clientId, String transport, String principal) {
    }

    /**
     * Fired when a client disconnects from a namespace.
     *
     * @param namespace  the namespace path
     * @param clientId   the session ID of the disconnected client
     */
    record ClientDisconnectedEvent(String namespace, String clientId) {
    }

    /**
     * Fired when a client successfully subscribes to a topic.
     *
     * @param namespace  the namespace path
     * @param clientId   the session ID
     * @param topic      the topic name
     */
    record SubscribedEvent(String namespace, String clientId, String topic) {
    }

    /**
     * Fired when a client unsubscribes from a topic.
     *
     * @param namespace  the namespace path
     * @param clientId   the session ID
     * @param topic      the topic name
     */
    record UnsubscribedEvent(String namespace, String clientId, String topic) {
    }

    /**
     * Fired when a message is successfully enqueued for a subscriber.
     *
     * @param namespace  the namespace path
     * @param clientId   the session ID of the target subscriber
     * @param topic      the topic name
     */
    record PublishAcceptedEvent(String namespace, String clientId, String topic) {
    }

    /**
     * Fired when a publish is rejected for a subscriber (e.g. queue full with
     * {@link OverflowAction#REJECT_NEW}).
     *
     * @param namespace   the namespace path
     * @param clientId    the session ID of the target subscriber
     * @param topic       the topic name
     * @param reasonCode  a short machine-readable code identifying the rejection cause
     * @param reason      a human-readable description of the rejection
     */
    record PublishRejectedEvent(String namespace, String clientId, String topic, String reasonCode, String reason) {
    }

    /**
     * Fired when a client's per-topic queue overflows and the configured
     * {@link OverflowAction} is applied.
     *
     * @param namespace  the namespace path
     * @param clientId   the session ID of the affected subscriber
     * @param topic      the topic name
     * @param reason     a description of how the overflow was handled
     */
    record QueueOverflowEvent(String namespace, String clientId, String topic, String reason) {
    }

    /**
     * Fired when a connection attempt is rejected by the {@link TokenValidator} or the
     * auth rate limiter.
     *
     * @param namespace       the namespace the client attempted to connect to
     * @param clientId        the session ID (may be transient)
     * @param remoteAddress   the client's remote IP address
     * @param reason          a human-readable description of the rejection
     */
    record AuthRejectedEvent(String namespace, String clientId, String remoteAddress, String reason) {
    }

    /**
     * Fired each time an {@link DeliveryMode#AT_LEAST_ONCE} message is retried.
     *
     * @param namespace   the namespace path
     * @param clientId    the session ID of the subscriber
     * @param topic       the topic name
     * @param messageId   the message being retried
     * @param retryCount  the current retry attempt number (1-based)
     */
    record RetryEvent(String namespace, String clientId, String topic, String messageId, int retryCount) {
    }

    /**
     * Fired when all retry attempts for an {@link DeliveryMode#AT_LEAST_ONCE} message are
     * exhausted without receiving an acknowledgement.
     *
     * @param namespace   the namespace path
     * @param clientId    the session ID of the subscriber
     * @param topic       the topic name
     * @param messageId   the message that was abandoned
     * @param retryCount  the total number of retry attempts made
     */
    record RetryExhaustedEvent(String namespace, String clientId, String topic, String messageId, int retryCount) {
    }
}
