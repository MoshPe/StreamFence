package io.streamfence.demo.dashboard;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PrometheusMetricsParser {

    private static final Pattern SAMPLE_PATTERN = Pattern.compile(
            "^(?<name>[a-zA-Z_:][a-zA-Z0-9_:]*)(?:\\{(?<labels>[^}]*)\\})?\\s+(?<value>[-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:[eE][-+]?\\d+)?)$");
    private static final Pattern LABEL_PATTERN = Pattern.compile("(?<key>[a-zA-Z_][a-zA-Z0-9_]*)=\"(?<value>(?:\\\\.|[^\"])*)\"");

    private PrometheusMetricsParser() {
    }

    public static DemoMetricsSnapshot parse(String scrape, Instant capturedAt) {
        Map<String, NamespaceAccumulator> namespaces = new LinkedHashMap<>();
        for (String rawLine : scrape.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Matcher matcher = SAMPLE_PATTERN.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            Map<String, String> labels = parseLabels(matcher.group("labels"));
            String namespace = labels.get("namespace");
            if (namespace == null || namespace.isBlank()) {
                continue;
            }
            NamespaceAccumulator accumulator = namespaces.computeIfAbsent(namespace, NamespaceAccumulator::new);
            long value = Math.round(Double.parseDouble(matcher.group("value")));
            accumulator.record(matcher.group("name"), value);
        }

        List<NamespaceSnapshot> namespaceSnapshots = namespaces.values().stream()
                .sorted(Comparator.comparing(NamespaceAccumulator::namespace))
                .map(NamespaceAccumulator::snapshot)
                .toList();

        long activeClients = 0L;
        long messagesPublished = 0L;
        long bytesPublished = 0L;
        long messagesReceived = 0L;
        long bytesReceived = 0L;
        long queueOverflow = 0L;
        long retryCount = 0L;
        long dropped = 0L;
        long coalesced = 0L;
        for (NamespaceSnapshot snapshot : namespaceSnapshots) {
            activeClients += snapshot.activeClients();
            messagesPublished += snapshot.messagesPublished();
            bytesPublished += snapshot.bytesPublished();
            messagesReceived += snapshot.messagesReceived();
            bytesReceived += snapshot.bytesReceived();
            queueOverflow += snapshot.queueOverflow();
            retryCount += snapshot.retryCount();
            dropped += snapshot.dropped();
            coalesced += snapshot.coalesced();
        }

        return new DemoMetricsSnapshot(
                capturedAt,
                activeClients,
                messagesPublished,
                bytesPublished,
                messagesReceived,
                bytesReceived,
                queueOverflow,
                retryCount,
                dropped,
                coalesced,
                namespaceSnapshots);
    }

    private static Map<String, String> parseLabels(String rawLabels) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (rawLabels == null || rawLabels.isBlank()) {
            return labels;
        }
        Matcher matcher = LABEL_PATTERN.matcher(rawLabels);
        while (matcher.find()) {
            labels.put(matcher.group("key"), matcher.group("value").replace("\\\"", "\""));
        }
        return labels;
    }

    private static final class NamespaceAccumulator {
        private final String namespace;
        private long activeClients;
        private long messagesPublished;
        private long bytesPublished;
        private long messagesReceived;
        private long bytesReceived;
        private long queueOverflow;
        private long retryCount;
        private long dropped;
        private long coalesced;

        private NamespaceAccumulator(String namespace) {
            this.namespace = namespace;
        }

        private String namespace() {
            return namespace;
        }

        private void record(String metricName, long value) {
            switch (metricName) {
                case "wsserver_connections_active" -> activeClients += value;
                case "wsserver_messages_published_total" -> messagesPublished += value;
                case "wsserver_bytes_published_total" -> bytesPublished += value;
                case "wsserver_messages_received_total" -> messagesReceived += value;
                case "wsserver_bytes_received_total" -> bytesReceived += value;
                case "wsserver_queue_overflow_total" -> queueOverflow += value;
                case "wsserver_retry_count_total" -> retryCount += value;
                case "wsserver_messages_dropped_total" -> dropped += value;
                case "wsserver_messages_coalesced_total" -> coalesced += value;
                default -> {
                    // Ignore unrelated metrics in the scrape body.
                }
            }
        }

        private NamespaceSnapshot snapshot() {
            return new NamespaceSnapshot(
                    namespace,
                    activeClients,
                    messagesPublished,
                    bytesPublished,
                    messagesReceived,
                    bytesReceived,
                    queueOverflow,
                    retryCount,
                    dropped,
                    coalesced);
        }
    }
}
