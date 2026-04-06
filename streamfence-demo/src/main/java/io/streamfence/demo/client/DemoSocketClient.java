package io.streamfence.demo.client;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.streamfence.demo.runtime.DemoEvent;
import io.streamfence.demo.runtime.DemoEventWriter;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;

public final class DemoSocketClient implements DemoClientSwarm.ClientHandle {

    private static final int MAX_PAYLOAD_PREVIEW_CHARS = 240;

    private final String processName;
    private final String namespace;
    private final String token;
    private final PrintStream out;
    private final Socket socket;
    private final Duration ackDelay;
    private final CountDownLatch connected = new CountDownLatch(1);
    private final Map<String, CountDownLatch> subscribeLatches = new ConcurrentHashMap<>();

    private DemoSocketClient(String processName, String namespace, String token, PrintStream out, Socket socket, Duration ackDelay) {
        this.processName = Objects.requireNonNull(processName, "processName");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.token = token;
        this.out = Objects.requireNonNull(out, "out");
        this.socket = Objects.requireNonNull(socket, "socket");
        this.ackDelay = Objects.requireNonNull(ackDelay, "ackDelay");
        registerHandlers();
    }

    public static DemoSocketClient connect(
            String processName,
            String namespace,
            URI serverUri,
            String token,
            PrintStream out) {
        return connect(processName, namespace, serverUri, token, out, Duration.ZERO);
    }

    public static DemoSocketClient connect(
            String processName,
            String namespace,
            URI serverUri,
            String token,
            PrintStream out,
            Duration ackDelay) {
        IO.Options options = IO.Options.builder()
                .setForceNew(true)
                .setReconnection(false)
                .setTransports(new String[]{"websocket"})
                .setTimeout(5000)
                .setPath("/socket.io")
                .build();
        URI namespaceUri = serverUri.resolve(namespace);
        if (token != null && !token.isBlank()) {
            String delimiter = namespaceUri.toString().contains("?") ? "&" : "?";
            namespaceUri = URI.create(namespaceUri + delimiter + "token="
                    + URLEncoder.encode(token, StandardCharsets.UTF_8));
        }
        return new DemoSocketClient(processName, namespace, token, out, IO.socket(namespaceUri, options), ackDelay);
    }

    public void connect() {
        socket.connect();
    }

    public boolean awaitConnected(long timeout, TimeUnit unit) throws InterruptedException {
        return connected.await(timeout, unit);
    }

    public void subscribe(String topic) {
        subscribeLatches.put(topic, new CountDownLatch(1));
        JSONObject request = requestWithTopic(topic);
        socket.emit("subscribe", request);
        write("outbound", "subscribe", topic, socket.id(), processName, "server", "subscribe requested", null, null);
    }

    public boolean awaitSubscribed(String topic, long timeout, TimeUnit unit) throws InterruptedException {
        CountDownLatch latch = subscribeLatches.get(topic);
        return latch != null && latch.await(timeout, unit);
    }

    public void publish(String topic, JSONObject payload) {
        try {
            JSONObject request = requestWithTopic(topic);
            request.put("payload", payload);
            socket.emit("publish", request);
            write("outbound", "publish", topic, socket.id(), processName, "server",
                    "publish sent", inspectPayload(payload), payloadPreview(payload.toString()));
        } catch (JSONException exception) {
            throw new IllegalStateException("Failed to build publish payload", exception);
        }
    }

    public void publishAck(String topic, String messageId) {
        socket.emit("ack", ackPayload(topic, messageId));
        write("outbound", "ack", topic, socket.id(), processName, "server", "ack sent", null, messageId);
    }

    private void registerHandlers() {
        socket.on(Socket.EVENT_CONNECT, args -> {
            connected.countDown();
            write("event", "connect", null, socket.id(), null, processName, "connected", null, null);
        });
        socket.on(Socket.EVENT_DISCONNECT, args -> write("event", "disconnect", null, socket.id(), processName, null, "disconnected", null, null));
        socket.on("subscribed", args -> write("event", "subscribed", topicFrom(args), socket.id(), "server", processName, "subscription confirmed", null, null));
        socket.on("unsubscribed", args -> write("event", "unsubscribed", topicFrom(args), socket.id(), "server", processName, "unsubscription confirmed", null, null));
        socket.on("error", args -> write("event", "error", null, socket.id(), "server", processName, "server error", null, stringify(args)));
        socket.on("topic-message", args -> handleTopicMessage(args));
    }

    private void handleTopicMessage(Object[] args) {
        if (args.length == 0 || !(args[0] instanceof JSONObject envelope)) {
            write("event", "topic_message", null, socket.id(), "server", processName, "received topic-message", null, null);
            return;
        }

        JSONObject metadata = envelope.optJSONObject("metadata");
        JSONObject payload = envelope.optJSONObject("payload");
        if (metadata == null || payload == null) {
            write("event", "topic_message", null, socket.id(), "server", processName,
                    "received topic-message", null, payloadPreview(envelope.toString()));
            return;
        }
        String topic = metadata.optString("topic", null);
        String messageId = metadata.optString("messageId", null);
        boolean ackRequired = metadata.optBoolean("ackRequired", false);
        PayloadMetadata payloadMetadata = inspectPayload(payload);
        write("inbound", "topic_message", topic, socket.id(),
                payloadMetadata.source() == null ? "server" : payloadMetadata.source(),
                processName,
                "received topic-message",
                payloadMetadata,
                payloadPreview(payload.toString()));

        if (ackRequired && topic != null && messageId != null && !messageId.isBlank()) {
            if (ackDelay.isZero()) {
                publishAck(topic, messageId);
            } else {
                CompletableFuture.runAsync(
                        () -> publishAck(topic, messageId),
                        CompletableFuture.delayedExecutor(ackDelay.toMillis(), TimeUnit.MILLISECONDS));
            }
        }
    }

    private String topicFrom(Object[] args) {
        if (args.length == 0 || !(args[0] instanceof JSONObject payload)) {
            return null;
        }
        String topic = payload.optString("topic", null);
        if (topic != null) {
            CountDownLatch latch = subscribeLatches.remove(topic);
            if (latch != null) {
                latch.countDown();
            }
        }
        return topic;
    }

    private String stringify(Object[] args) {
        if (args.length == 0) {
            return null;
        }
        return payloadPreview(String.valueOf(args[0]));
    }

    static String payloadPreview(String payload) {
        if (payload == null) {
            return null;
        }
        if (payload.length() <= MAX_PAYLOAD_PREVIEW_CHARS) {
            return payload;
        }
        return payload.substring(0, MAX_PAYLOAD_PREVIEW_CHARS - 3) + "...";
    }

    static PayloadMetadata inspectPayload(JSONObject payload) {
        if (payload == null) {
            return new PayloadMetadata(null, null, null);
        }
        String source = payload.optString("source", null);
        if ((source == null || source.isBlank()) && payload.optJSONObject("context") != null) {
            source = payload.optJSONObject("context").optString("source", null);
        }
        String contentType = payload.has("contentType") && !payload.isNull("contentType")
                ? payload.optString("contentType", null)
                : null;
        Long declaredSizeBytes = payload.has("declaredSizeBytes") && !payload.isNull("declaredSizeBytes")
                ? payload.optLong("declaredSizeBytes")
                : null;
        return new PayloadMetadata(source, contentType, declaredSizeBytes);
    }

    private void write(
            String direction,
            String category,
            String topic,
            String clientId,
            String sender,
            String receiver,
            String summary,
            PayloadMetadata payloadMetadata,
            String payloadPreview) {
        DemoEventWriter.write(out, new DemoEvent(
                Instant.now().toString(),
                "client",
                processName,
                direction,
                category,
                namespace,
                topic,
                clientId,
                sender,
                receiver,
                summary,
                payloadMetadata == null ? null : payloadMetadata.source(),
                payloadMetadata == null ? null : payloadMetadata.contentType(),
                payloadMetadata == null ? null : payloadMetadata.declaredSizeBytes(),
                payloadPreview));
    }

    record PayloadMetadata(
            String source,
            String contentType,
            Long declaredSizeBytes
    ) {
    }

    private JSONObject requestWithTopic(String topic) {
        try {
            JSONObject request = new JSONObject().put("topic", topic);
            if (token != null) {
                request.put("token", token);
            }
            return request;
        } catch (JSONException exception) {
            throw new IllegalStateException("Failed to build demo client request", exception);
        }
    }

    private JSONObject ackPayload(String topic, String messageId) {
        try {
            return new JSONObject()
                    .put("topic", topic)
                    .put("messageId", messageId);
        } catch (JSONException exception) {
            throw new IllegalStateException("Failed to build ack payload", exception);
        }
    }

    @Override
    public void close() {
        socket.disconnect();
        socket.close();
    }
}
