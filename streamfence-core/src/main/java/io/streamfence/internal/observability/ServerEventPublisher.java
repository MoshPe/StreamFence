package io.streamfence.internal.observability;

import io.streamfence.ServerEventListener;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ServerEventPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(ServerEventPublisher.class);
    private static final ServerEventPublisher NO_OP = new ServerEventPublisher(List.of());

    private final List<ServerEventListener> listeners;

    public ServerEventPublisher(List<ServerEventListener> listeners) {
        this.listeners = List.copyOf(Objects.requireNonNull(listeners, "listeners"));
    }

    public static ServerEventPublisher noOp() {
        return NO_OP;
    }

    public void serverStarting(String host, int port, int managementPort) {
        publish(listener -> listener.onServerStarting(new ServerEventListener.ServerStartingEvent(host, port, managementPort)));
    }

    public void serverStarted(String host, int port, int managementPort) {
        publish(listener -> listener.onServerStarted(new ServerEventListener.ServerStartedEvent(host, port, managementPort)));
    }

    public void serverStopping(String host, int port, int managementPort) {
        publish(listener -> listener.onServerStopping(new ServerEventListener.ServerStoppingEvent(host, port, managementPort)));
    }

    public void serverStopped(String host, int port, int managementPort) {
        publish(listener -> listener.onServerStopped(new ServerEventListener.ServerStoppedEvent(host, port, managementPort)));
    }

    public void clientConnected(String namespace, String clientId, String transport, String principal) {
        publish(listener -> listener.onClientConnected(new ServerEventListener.ClientConnectedEvent(namespace, clientId, transport, principal)));
    }

    public void clientDisconnected(String namespace, String clientId) {
        publish(listener -> listener.onClientDisconnected(new ServerEventListener.ClientDisconnectedEvent(namespace, clientId)));
    }

    public void subscribed(String namespace, String clientId, String topic) {
        publish(listener -> listener.onSubscribed(new ServerEventListener.SubscribedEvent(namespace, clientId, topic)));
    }

    public void unsubscribed(String namespace, String clientId, String topic) {
        publish(listener -> listener.onUnsubscribed(new ServerEventListener.UnsubscribedEvent(namespace, clientId, topic)));
    }

    public void publishAccepted(String namespace, String clientId, String topic) {
        publish(listener -> listener.onPublishAccepted(new ServerEventListener.PublishAcceptedEvent(namespace, clientId, topic)));
    }

    public void publishRejected(String namespace, String clientId, String topic, String reasonCode, String reason) {
        publish(listener -> listener.onPublishRejected(new ServerEventListener.PublishRejectedEvent(namespace, clientId, topic, reasonCode, reason)));
    }

    public void queueOverflow(String namespace, String clientId, String topic, String reason) {
        publish(listener -> listener.onQueueOverflow(new ServerEventListener.QueueOverflowEvent(namespace, clientId, topic, reason)));
    }

    public void authRejected(String namespace, String clientId, String remoteAddress, String reason) {
        publish(listener -> listener.onAuthRejected(new ServerEventListener.AuthRejectedEvent(namespace, clientId, remoteAddress, reason)));
    }

    public void retry(String namespace, String clientId, String topic, String messageId, int retryCount) {
        publish(listener -> listener.onRetry(new ServerEventListener.RetryEvent(namespace, clientId, topic, messageId, retryCount)));
    }

    public void retryExhausted(String namespace, String clientId, String topic, String messageId, int retryCount) {
        publish(listener -> listener.onRetryExhausted(new ServerEventListener.RetryExhaustedEvent(namespace, clientId, topic, messageId, retryCount)));
    }

    private void publish(Consumer<ServerEventListener> callback) {
        for (ServerEventListener listener : listeners) {
            try {
                callback.accept(listener);
            } catch (RuntimeException exception) {
                LOG.warn("event=listener_failure listener={} error={}", listener.getClass().getName(), exception.getMessage(), exception);
            }
        }
    }
}
