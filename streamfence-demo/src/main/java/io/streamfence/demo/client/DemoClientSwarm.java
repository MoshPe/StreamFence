package io.streamfence.demo.client;

import io.streamfence.demo.runtime.DemoPersona;
import java.io.PrintStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

public final class DemoClientSwarm implements AutoCloseable {

    private final DemoPersona persona;
    private final URI serverUri;
    private final PrintStream out;
    private final ClientFactory clientFactory;
    private final List<ClientHandle> clients = new CopyOnWriteArrayList<>();
    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private ScheduledExecutorService scheduler;

    public DemoClientSwarm(DemoPersona persona, URI serverUri, PrintStream out) {
        this(persona, serverUri, out, DemoSocketClient::connect);
    }

    DemoClientSwarm(DemoPersona persona, URI serverUri, PrintStream out, ClientFactory clientFactory) {
        this.persona = Objects.requireNonNull(persona, "persona");
        this.serverUri = Objects.requireNonNull(serverUri, "serverUri");
        this.out = Objects.requireNonNull(out, "out");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
    }

    public void start() throws InterruptedException {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        String token = persona.tokenRequired() ? "change-me" : null;
        for (int index = 0; index < persona.connectionCount(); index++) {
            ClientHandle client = clientFactory.create(
                    persona.name(),
                    persona.namespace(),
                    serverUri,
                    token,
                    out,
                    persona.ackDelay());
            clients.add(client);
            client.connect();
        }
        for (ClientHandle client : clients) {
            if (!client.awaitConnected(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for swarm client connection: " + persona.name());
            }
            for (String topic : persona.topics()) {
                client.subscribe(topic);
                if (!client.awaitSubscribed(topic, 10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for swarm subscribe confirmation: " + topic);
                }
            }
        }

        Duration cadence = persona.publishCadence();
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "wsserver-demo-swarm-" + persona.name());
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(
                this::publishOnce,
                cadence.toMillis(),
                cadence.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    public void pause() {
        paused.set(true);
    }

    public void resume() {
        paused.set(false);
    }

    public boolean paused() {
        return paused.get();
    }

    public void publishOnce() {
        if (paused.get()) {
            return;
        }
        List<JSONObject> payloads = new ArrayList<>(clients.size());
        for (int index = 0; index < clients.size(); index++) {
            payloads.add(DemoPayloadFactory.payloadFor(persona));
        }
        for (int clientIndex = 0; clientIndex < clients.size(); clientIndex++) {
            ClientHandle client = clients.get(clientIndex);
            for (String topic : persona.topics()) {
                client.publish(topic, payloads.get(clientIndex));
            }
        }
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        for (ClientHandle client : clients) {
            try {
                client.close();
            } catch (Exception ignored) {
                // Best effort during demo shutdown.
            }
        }
        clients.clear();
    }

    interface ClientFactory {
        ClientHandle create(String processName, String namespace, URI serverUri, String token, PrintStream out, Duration ackDelay);
    }

    interface ClientHandle extends AutoCloseable {
        void connect();

        boolean awaitConnected(long timeout, TimeUnit unit) throws InterruptedException;

        void subscribe(String topic);

        boolean awaitSubscribed(String topic, long timeout, TimeUnit unit) throws InterruptedException;

        void publish(String topic, JSONObject payload);

        @Override
        void close();
    }
}
