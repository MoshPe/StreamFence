package io.streamfence.demo.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.streamfence.demo.runtime.DemoPayloadKind;
import io.streamfence.demo.runtime.DemoPersona;
import java.io.PrintStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class DemoClientSwarmTest {

    @Test
    void startConnectsTheFullSwarmAndPublishesAcrossAllConnections() throws Exception {
        DemoPersona persona = new DemoPersona(
                "price-bot",
                "/non-reliable",
                List.of("prices"),
                false,
                Duration.ofHours(1),
                DemoPayloadKind.JSON,
                3,
                Duration.ZERO);
        List<FakeClient> createdClients = new ArrayList<>();

        try (DemoClientSwarm swarm = new DemoClientSwarm(
                persona,
                URI.create("http://127.0.0.1:9092"),
                new PrintStream(OutputStream.nullOutputStream()),
                (processName, namespace, serverUri, token, out, ackDelay) -> {
                    FakeClient client = new FakeClient(processName, namespace, serverUri, token, ackDelay);
                    createdClients.add(client);
                    return client;
                })) {
            swarm.start();
            swarm.publishOnce();

            assertThat(createdClients).hasSize(3);
            assertThat(createdClients).allSatisfy(client -> {
                assertThat(client.connected).isTrue();
                assertThat(client.subscriptions).containsExactly("prices");
                assertThat(client.publishes).hasSize(1);
                assertThat(client.publishes.getFirst().topic()).isEqualTo("prices");
            });
        }
    }

    @Test
    void pausePreventsPublishingUntilScenarioIsResumed() throws Exception {
        DemoPersona persona = new DemoPersona(
                "alerts-bot",
                "/reliable",
                List.of("alerts"),
                true,
                Duration.ofHours(1),
                DemoPayloadKind.JSON,
                2,
                Duration.ofMillis(900));
        List<FakeClient> createdClients = new ArrayList<>();

        try (DemoClientSwarm swarm = new DemoClientSwarm(
                persona,
                URI.create("http://127.0.0.1:9092"),
                new PrintStream(OutputStream.nullOutputStream()),
                (processName, namespace, serverUri, token, out, ackDelay) -> {
                    FakeClient client = new FakeClient(processName, namespace, serverUri, token, ackDelay);
                    createdClients.add(client);
                    return client;
                })) {
            swarm.start();
            swarm.pause();
            swarm.publishOnce();

            assertThat(createdClients).allSatisfy(client -> assertThat(client.publishes).isEmpty());

            swarm.resume();
            swarm.publishOnce();

            assertThat(createdClients).allSatisfy(client -> assertThat(client.publishes).hasSize(1));
        }
    }

    private static final class FakeClient implements DemoClientSwarm.ClientHandle {
        private final String processName;
        private final String namespace;
        private final URI serverUri;
        private final String token;
        private final Duration ackDelay;
        private final List<String> subscriptions = new ArrayList<>();
        private final List<PublishInvocation> publishes = new ArrayList<>();
        private boolean connected;

        private FakeClient(String processName, String namespace, URI serverUri, String token, Duration ackDelay) {
            this.processName = processName;
            this.namespace = namespace;
            this.serverUri = serverUri;
            this.token = token;
            this.ackDelay = ackDelay;
        }

        @Override
        public void connect() {
            connected = true;
        }

        @Override
        public boolean awaitConnected(long timeout, TimeUnit unit) {
            return connected;
        }

        @Override
        public void subscribe(String topic) {
            subscriptions.add(topic);
        }

        @Override
        public boolean awaitSubscribed(String topic, long timeout, TimeUnit unit) {
            return subscriptions.contains(topic);
        }

        @Override
        public void publish(String topic, JSONObject payload) {
            publishes.add(new PublishInvocation(topic, payload.toString()));
        }

        @Override
        public void close() {
        }
    }

    private record PublishInvocation(String topic, String payload) {
    }
}
