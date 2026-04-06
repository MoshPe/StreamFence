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

public final class ServerMetrics {

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final Map<String, AtomicInteger> activeConnections = new ConcurrentHashMap<>();
    // Counter cache keyed by "metricName|namespace|topic|reason". Micrometer's
    // Counter.builder().register() is idempotent but walks its meter map on
    // every call — at 250 clients × 100 msg/s × 2 publish counters that is
    // ~50k map lookups/s on the publish hot path. Local caching is a trivial
    // one-liner and sidesteps the hit entirely.
    private final Map<String, Counter> counterCache = new ConcurrentHashMap<>();

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

    public MeterRegistry registry() {
        return registry;
    }

    /**
     * Produces a Prometheus text-format scrape body. Called by the management
     * HTTP server on each GET /metrics.
     */
    public String scrape() {
        return registry.scrape();
    }

    public void recordConnect(String namespace) {
        namespaceGauge(namespace).incrementAndGet();
        counter("wsserver.connections.opened", namespace, null, null).increment();
    }

    public void recordDisconnect(String namespace) {
        namespaceGauge(namespace).decrementAndGet();
        counter("wsserver.connections.closed", namespace, null, null).increment();
    }

    public void recordPublish(String namespace, String topic, long bytes) {
        counter("wsserver.messages.published", namespace, topic, null).increment();
        counter("wsserver.bytes.published", namespace, topic, null).increment(bytes);
    }

    public void recordReceived(String namespace, String topic, long bytes) {
        counter("wsserver.messages.received", namespace, topic, null).increment();
        counter("wsserver.bytes.received", namespace, topic, null).increment(bytes);
    }

    public void recordQueueOverflow(String namespace, String topic, String reason) {
        counter("wsserver.queue.overflow", namespace, topic, reason).increment();
    }

    public void recordRetry(String namespace, String topic) {
        counter("wsserver.retry.count", namespace, topic, null).increment();
    }

    public void recordRetryExhausted(String namespace, String topic) {
        counter("wsserver.retry.exhausted", namespace, topic, null).increment();
    }

    public void recordDropped(String namespace, String topic) {
        counter("wsserver.messages.dropped", namespace, topic, null).increment();
    }

    public void recordCoalesced(String namespace, String topic) {
        counter("wsserver.messages.coalesced", namespace, topic, null).increment();
    }

    public void recordAuthRejected(String namespace) {
        counter("wsserver.auth.rejected", namespace, null, null).increment();
    }

    public void recordAuthRateLimited(String namespace) {
        counter("wsserver.auth.rate_limited", namespace, null, null).increment();
    }

    /**
     * Registers pull-gauges that report the current lane count, max queue
     * depth, and max queued bytes for a given (namespace, topic). The supplier
     * is invoked only at scrape time, so steady-state publish cost is zero.
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
