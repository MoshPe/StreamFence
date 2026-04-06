package io.streamfence.demo.server;

import io.streamfence.ServerEventListener;
import io.streamfence.demo.runtime.DemoEvent;
import io.streamfence.demo.runtime.DemoEventWriter;
import java.io.PrintStream;
import java.time.Instant;
import java.util.Objects;

public final class DemoServerListener implements ServerEventListener {

    private final PrintStream out;
    private final String processName;

    public DemoServerListener(PrintStream out) {
        this(out, "demo-server");
    }

    DemoServerListener(PrintStream out, String processName) {
        this.out = Objects.requireNonNull(out, "out");
        this.processName = Objects.requireNonNull(processName, "processName");
    }

    @Override
    public void onServerStarting(ServerStartingEvent event) {
        write("server_starting", null, null, null, null, processName,
                "server starting on " + event.host() + ":" + event.port());
    }

    @Override
    public void onServerStarted(ServerStartedEvent event) {
        write("server_started", null, null, null, null, processName,
                "server started on " + event.host() + ":" + event.port());
    }

    @Override
    public void onServerStopping(ServerStoppingEvent event) {
        write("server_stopping", null, null, null, null, processName,
                "server stopping on " + event.host() + ":" + event.port());
    }

    @Override
    public void onServerStopped(ServerStoppedEvent event) {
        write("server_stopped", null, null, null, null, processName,
                "server stopped on " + event.host() + ":" + event.port());
    }

    @Override
    public void onClientConnected(ClientConnectedEvent event) {
        write("client_connected", event.namespace(), null, event.clientId(), event.clientId(), "server",
                "client connected via " + event.transport());
    }

    @Override
    public void onClientDisconnected(ClientDisconnectedEvent event) {
        write("client_disconnected", event.namespace(), null, event.clientId(), event.clientId(), "server",
                "client disconnected");
    }

    @Override
    public void onSubscribed(SubscribedEvent event) {
        write("subscribed", event.namespace(), event.topic(), event.clientId(), event.clientId(), "server",
                "client subscribed");
    }

    @Override
    public void onUnsubscribed(UnsubscribedEvent event) {
        write("unsubscribed", event.namespace(), event.topic(), event.clientId(), event.clientId(), "server",
                "client unsubscribed");
    }

    @Override
    public void onPublishAccepted(PublishAcceptedEvent event) {
        write("publish_accepted", event.namespace(), event.topic(), event.clientId(), event.clientId(), "server",
                "publish accepted");
    }

    @Override
    public void onPublishRejected(PublishRejectedEvent event) {
        write("publish_rejected", event.namespace(), event.topic(), event.clientId(), event.clientId(), "server",
                event.reasonCode() + ": " + event.reason());
    }

    @Override
    public void onQueueOverflow(QueueOverflowEvent event) {
        write("queue_overflow", event.namespace(), event.topic(), event.clientId(), event.clientId(), "server",
                event.reason());
    }

    @Override
    public void onAuthRejected(AuthRejectedEvent event) {
        write("auth_rejected", event.namespace(), null, event.clientId(), event.clientId(), "server",
                event.reason() + " from " + event.remoteAddress());
    }

    @Override
    public void onRetry(RetryEvent event) {
        write("retry", event.namespace(), event.topic(), event.clientId(), "server", event.clientId(),
                "retry " + event.retryCount() + " message=" + event.messageId());
    }

    @Override
    public void onRetryExhausted(RetryExhaustedEvent event) {
        write("retry_exhausted", event.namespace(), event.topic(), event.clientId(), "server", event.clientId(),
                "retry exhausted after " + event.retryCount() + " attempts message=" + event.messageId());
    }

    private void write(String category, String namespace, String topic, String clientId, String sender, String receiver, String summary) {
        DemoEventWriter.write(out, new DemoEvent(
                Instant.now().toString(),
                "server",
                processName,
                "event",
                category,
                namespace,
                topic,
                clientId,
                sender,
                receiver,
                summary,
                null,
                null,
                null,
                null));
    }
}
