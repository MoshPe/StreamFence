package io.streamfence;

public interface ServerEventListener {

    default void onServerStarting(ServerStartingEvent event) {
    }

    default void onServerStarted(ServerStartedEvent event) {
    }

    default void onServerStopping(ServerStoppingEvent event) {
    }

    default void onServerStopped(ServerStoppedEvent event) {
    }

    default void onClientConnected(ClientConnectedEvent event) {
    }

    default void onClientDisconnected(ClientDisconnectedEvent event) {
    }

    default void onSubscribed(SubscribedEvent event) {
    }

    default void onUnsubscribed(UnsubscribedEvent event) {
    }

    default void onPublishAccepted(PublishAcceptedEvent event) {
    }

    default void onPublishRejected(PublishRejectedEvent event) {
    }

    default void onQueueOverflow(QueueOverflowEvent event) {
    }

    default void onAuthRejected(AuthRejectedEvent event) {
    }

    default void onRetry(RetryEvent event) {
    }

    default void onRetryExhausted(RetryExhaustedEvent event) {
    }

    record ServerStartingEvent(String host, int port, int managementPort) {
    }

    record ServerStartedEvent(String host, int port, int managementPort) {
    }

    record ServerStoppingEvent(String host, int port, int managementPort) {
    }

    record ServerStoppedEvent(String host, int port, int managementPort) {
    }

    record ClientConnectedEvent(String namespace, String clientId, String transport, String principal) {
    }

    record ClientDisconnectedEvent(String namespace, String clientId) {
    }

    record SubscribedEvent(String namespace, String clientId, String topic) {
    }

    record UnsubscribedEvent(String namespace, String clientId, String topic) {
    }

    record PublishAcceptedEvent(String namespace, String clientId, String topic) {
    }

    record PublishRejectedEvent(String namespace, String clientId, String topic, String reasonCode, String reason) {
    }

    record QueueOverflowEvent(String namespace, String clientId, String topic, String reason) {
    }

    record AuthRejectedEvent(String namespace, String clientId, String remoteAddress, String reason) {
    }

    record RetryEvent(String namespace, String clientId, String topic, String messageId, int retryCount) {
    }

    record RetryExhaustedEvent(String namespace, String clientId, String topic, String messageId, int retryCount) {
    }
}
