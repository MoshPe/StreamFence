package io.streamfence.internal.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.corundumstudio.socketio.SocketIOClient;
import io.streamfence.AuthMode;
import io.streamfence.DeliveryMode;
import io.streamfence.internal.config.NamespaceConfig;
import io.streamfence.OverflowAction;
import io.streamfence.internal.config.ServerConfig;
import io.streamfence.internal.config.TopicPolicy;
import io.streamfence.TransportMode;
import io.streamfence.ServerMetrics;
import io.streamfence.internal.protocol.TopicMessageEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

class TopicDispatcherTest {

    @Test
    void closingWithUnackedReliableMessageDoesNotRescheduleAfterShutdown() {
        DispatcherFixture fixture = new DispatcherFixture(1);
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread.UncaughtExceptionHandler originalHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> uncaught.compareAndSet(null, throwable));
        try (TopicDispatcher dispatcher = fixture.dispatcher()) {
            dispatcher.publish("/reliable", "alerts", Map.of("seq", 1));

            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(fixture.recordingClient().messageIds()).hasSize(1));
        } finally {
            Awaitility.await()
                    .pollDelay(Duration.ofMillis(200))
                    .atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(uncaught.get()).isNull());
            Thread.setDefaultUncaughtExceptionHandler(originalHandler);
        }
    }

    @Test
    void outOfOrderAckDoesNotBlockLaterReliableMessages() {
        DispatcherFixture fixture = new DispatcherFixture(2);

        try (TopicDispatcher dispatcher = fixture.dispatcher()) {
            dispatcher.publish("/reliable", "alerts", Map.of("seq", 1));
            dispatcher.publish("/reliable", "alerts", Map.of("seq", 2));
            dispatcher.publish("/reliable", "alerts", Map.of("seq", 3));

            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(fixture.recordingClient().messageIds()).hasSize(2));

            List<String> firstTwoMessageIds = fixture.recordingClient().messageIds();
            dispatcher.acknowledge("client-1", "/reliable", "alerts", firstTwoMessageIds.get(1));
            dispatcher.acknowledge("client-1", "/reliable", "alerts", firstTwoMessageIds.get(0));

            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(fixture.recordingClient().messageIds()).hasSize(3));
        }
    }

    private static final class DispatcherFixture {
        private final TopicPolicy topicPolicy;
        private final TopicRegistry topicRegistry;
        private final ClientSessionRegistry sessionRegistry;
        private final AckTracker ackTracker;
        private final RetryService retryService;
        private final ServerMetrics metrics;
        private final RecordingClient recordingClient;

        private DispatcherFixture(int maxInFlight) {
            this.topicPolicy = reliablePolicy(maxInFlight);
            ServerConfig serverConfig = new ServerConfig(
                    "127.0.0.1",
                    9092,
                    TransportMode.WS,
                    null,
                    15000,
                    30000,
                    6291456,
                    6291456,
                    false,
                    false,
                    null,
                    AuthMode.NONE,
                    Map.of(),
                    Map.of(
                            "/non-reliable", new NamespaceConfig(false),
                            "/reliable", new NamespaceConfig(false),
                            "/bulk", new NamespaceConfig(false)
                    ),
                    List.of(topicPolicy)
            );
            this.topicRegistry = new TopicRegistry(serverConfig);
            this.sessionRegistry = new ClientSessionRegistry();
            this.ackTracker = new AckTracker();
            this.retryService = new RetryService(ackTracker);
            this.metrics = new ServerMetrics();
            this.recordingClient = new RecordingClient();
            ClientSessionState sessionState = new ClientSessionState("client-1", "/reliable", recordingClient.client());
            sessionRegistry.register(sessionState);
            sessionRegistry.subscribe(sessionState, "alerts", topicPolicy);
        }

        private TopicDispatcher dispatcher() {
            return new TopicDispatcher(
                    topicRegistry,
                    sessionRegistry,
                    ackTracker,
                    retryService,
                    metrics,
                    1,
                    new ObjectMapper()
            );
        }

        private RecordingClient recordingClient() {
            return recordingClient;
        }
    }

    private static TopicPolicy reliablePolicy(int maxInFlight) {
        return new TopicPolicy(
                "/reliable",
                "alerts",
                DeliveryMode.AT_LEAST_ONCE,
                OverflowAction.REJECT_NEW,
                16,
                65536,
                1000,
                1,
                false,
                true,
                false,
                maxInFlight
        );
    }

    private static final class RecordingClient {
        private final List<String> messageIds = java.util.Collections.synchronizedList(new ArrayList<>());
        private final SocketIOClient client = (SocketIOClient) Proxy.newProxyInstance(
                SocketIOClient.class.getClassLoader(),
                new Class<?>[]{SocketIOClient.class},
                (proxy, method, args) -> {
                    if ("sendEvent".equals(method.getName())) {
                        Object[] eventArgs = args != null && args.length > 1 && args[1] instanceof Object[] array
                                ? array
                                : new Object[0];
                        if (eventArgs.length > 0 && eventArgs[0] instanceof TopicMessageEnvelope envelope) {
                            messageIds.add(envelope.metadata().messageId());
                        }
                        return null;
                    }
                    if ("getSessionId".equals(method.getName())) {
                        return UUID.fromString("00000000-0000-0000-0000-000000000001");
                    }
                    if ("toString".equals(method.getName())) {
                        return "RecordingClient";
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) {
                        return false;
                    }
                    if (returnType == int.class) {
                        return 0;
                    }
                    if (returnType == long.class) {
                        return 0L;
                    }
                    if (returnType == double.class) {
                        return 0D;
                    }
                    if (returnType == float.class) {
                        return 0F;
                    }
                    return null;
                });

        private SocketIOClient client() {
            return client;
        }

        private List<String> messageIds() {
            synchronized (messageIds) {
                return List.copyOf(messageIds);
            }
        }
    }
}
