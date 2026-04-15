package io.streamfence;

import io.streamfence.internal.config.TopicPolicy;
import io.streamfence.internal.delivery.ClientLane;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Micrometer-backed metrics collector for a running {@link SocketIoServer}.
 *
 * <p>A {@code ServerMetrics} instance is created automatically by each server and is
 * accessible via {@link SocketIoServer#metrics()}. It exposes standard JVM and process
 * binders in addition to the Socket.IO-specific counters and gauges listed below.
 *
 * <p>When a {@code managementPort} is configured the server also starts a lightweight HTTP
 * endpoint that serves the Prometheus text-format scrape body produced by {@link #scrape()}.
 */
public final class ServerMetrics {

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final Map<String, AtomicInteger> activeConnections = new ConcurrentHashMap<>();
    // Counter cache keyed by "metricName|namespace|topic|reason". Micrometer's
    // Counter.builder().register() is idempotent but walks its meter map on
    // every call — at 250 clients × 100 msg/s × 2 publish counters that is
    // ~50k map lookups/s on the publish hot path. Local caching is a trivial
    // one-liner and sidesteps the hit entirely.
    private final Map<String, Counter> counterCache = new ConcurrentHashMap<>();

    /** Creates a new {@code ServerMetrics} and binds standard JVM and process meters. */
    public ServerMetrics() {
        // JVM / process binders — free metrics on top of the existing
        // counters. GC and memory must not be closed during the server's
        // lifetime, so we don't hold references we'd need to stop.
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ClassLoaderMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        new FileDescriptorMetrics().bindTo(registry);
        new UptimeMetrics().bindTo(registry);
    }

    /**
     * Returns the underlying Micrometer {@link MeterRegistry}. Use this to register
     * additional application-specific meters that will appear in the same scrape body.
     *
     * @return the shared {@code MeterRegistry}
     */
    public MeterRegistry registry() {
        return registry;
    }

    /**
     * Produces a Prometheus text-format scrape body. Called by the management HTTP server
     * on each {@code GET /metrics}.
     *
     * @return the Prometheus exposition text
     */
    public String scrape() {
        return registry.scrape();
    }

    /**
     * Records a new client connection on {@code namespace}.
     *
     * @param namespace the namespace path
     */
    public void recordConnect(String namespace) {
        namespaceGauge(namespace).incrementAndGet();
        counter("wsserver.connections.opened", namespace, null, null).increment();
    }

    /**
     * Records a client disconnection from {@code namespace}.
     *
     * @param namespace the namespace path
     */
    public void recordDisconnect(String namespace) {
        namespaceGauge(namespace).decrementAndGet();
        counter("wsserver.connections.closed", namespace, null, null).increment();
    }

    /**
     * Records an outbound message published to {@code topic} on {@code namespace}.
     *
     * @param namespace the namespace path
     * @param topic     the topic name
     * @param bytes     estimated size of the message in bytes
     */
    public void recordPublish(String namespace, String topic, long bytes) {
        counter("wsserver.messages.published", namespace, topic, null).increment();
        counter("wsserver.bytes.published", namespace, topic, null).increment(bytes);
    }

    /**
     * Records an inbound message received from a client on {@code topic}.
     *
     * @param namespace the namespace path
     * @param topic     the topic name
     * @param bytes     estimated size of the message in bytes
     */
    public void recordReceived(String namespace, String topic, long bytes) {
        counter("wsserver.messages.received", namespace, topic, null).increment();
        counter("wsserver.bytes.received", namespace, topic, null).increment(bytes);
    }

    /**
     * Records a queue overflow event for {@code topic} on {@code namespace}.
     *
     * @param namespace the namespace path
     * @param topic     the topic name
     * @param reason    a short description of how the overflow was handled
     */
    public void recordQueueOverflow(String namespace, String topic, String reason) {
        counter("wsserver.queue.overflow", namespace, topic, reason).increment();
    }

    /**
     * Records one retry attempt for an unacknowledged message on {@code topic}.
     *
     * @param namespace the namespace path
     * @param topic     the topic name
     */
    public void recordRetry(String namespace, String topic) {
        counter("wsserver.retry.count", namespace, topic, null).increment();
    }

    /**
     * Records a message whose retry budget was exhausted on {@code topic}.
     *
     * @param namespace the namespace path
     * @param topic     the topic name
     */
    public void recordRetryExhausted(String namespace, String topic) {
        counter("wsserver.retry.exhausted", namespace, topic, null).increment();
    }

    /**
     * Records a message dropped due to {@link OverflowAction#DROP_OLDEST}.
     *
     * @param namespace the namespace path
     * @param topic     the topic name
     */
    public void recordDropped(String namespace, String topic) {
        counter("wsserver.messages.dropped", namespace, topic, null).increment();
    }

    /**
     * Records a message coalesced due to {@link OverflowAction#COALESCE}.
     *
     * @param namespace the namespace path
     * @param topic     the topic name
     */
    public void recordCoalesced(String namespace, String topic) {
        counter("wsserver.messages.coalesced", namespace, topic, null).increment();
    }

    /**
     * Records a message spilled to disk due to {@link OverflowAction#SPILL_TO_DISK}.
     *
     * @param namespace the namespace path
     * @param topic     the topic name
     */
    public void recordSpill(String namespace, String topic) {
        counter("wsserver.messages.spilled", namespace, topic, null).increment();
    }

    /**
     * Records an authentication rejection on {@code namespace}.
     *
     * @param namespace the namespace path
     */
    public void recordAuthRejected(String namespace) {
        counter("wsserver.auth.rejected", namespace, null, null).increment();
    }

    /**
     * Records an authentication attempt rejected by the rate limiter on {@code namespace}.
     *
     * @param namespace the namespace path
     */
    public void recordAuthRateLimited(String namespace) {
        counter("wsserver.auth.rate_limited", namespace, null, null).increment();
    }

    /**
     * Registers pull-gauges that report the current lane count, max queue depth, and max
     * queued bytes for a given (namespace, topic). The supplier is invoked only at scrape
     * time, so steady-state publish cost is zero.
     *
     * @param policy        the topic policy identifying the namespace and topic
     * @param lanesSupplier a supplier of the current set of active {@link ClientLane}s for
     *                      the given topic; queried only during Prometheus scrapes
     */
    public void registerTopicGauges(TopicPolicy policy, Supplier<Collection<ClientLane>> lanesSupplier) {
        Gauge.builder("wsserver.lane.count", lanesSupplier, s -> s.get().size())
                .tag("namespace", policy.namespace())
                .tag("topic", policy.topic())
                .register(registry);
        Gauge.builder("wsserver.lane.depth.max", lanesSupplier, s -> {
                    int max = 0;
                    for (ClientLane lane : s.get()) {
                        int size = lane.size();
                        if (size > max) {
                            max = size;
                        }
                    }
                    return max;
                })
                .tag("namespace", policy.namespace())
                .tag("topic", policy.topic())
                .register(registry);
        Gauge.builder("wsserver.lane.bytes.max", lanesSupplier, s -> {
                    long max = 0L;
                    for (ClientLane lane : s.get()) {
                        long bytes = lane.queuedBytes();
                        if (bytes > max) {
                            max = bytes;
                        }
                    }
                    return max;
                })
                .tag("namespace", policy.namespace())
                .tag("topic", policy.topic())
                .register(registry);
    }

    private Counter counter(String name, String namespace, String topic, String reason) {
        String cacheKey = name + '|' + namespace + '|' + (topic == null ? "" : topic) + '|' + (reason == null ? "" : reason);
        return counterCache.computeIfAbsent(cacheKey, ignored -> {
            Counter.Builder builder = Counter.builder(name).tag("namespace", namespace);
            if (topic != null) {
                builder.tag("topic", topic);
            }
            if (reason != null) {
                builder.tag("reason", reason);
            }
            return builder.register(registry);
        });
    }

    private AtomicInteger namespaceGauge(String namespace) {
        return activeConnections.computeIfAbsent(namespace, ignored -> {
            AtomicInteger atomicInteger = new AtomicInteger();
            Gauge.builder("wsserver.connections.active", atomicInteger, AtomicInteger::get)
                    .tag("namespace", namespace)
                    .register(registry);
            return atomicInteger;
        });
    }
}
