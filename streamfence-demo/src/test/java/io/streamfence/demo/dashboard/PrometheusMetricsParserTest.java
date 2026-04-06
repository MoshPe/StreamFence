package io.streamfence.demo.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PrometheusMetricsParserTest {

    @Test
    void parsesNamespaceCountersFromPrometheusScrape() {
        DemoMetricsSnapshot snapshot = PrometheusMetricsParser.parse("""
                wsserver_connections_active{namespace="/non-reliable"} 12.0
                wsserver_messages_published_total{namespace="/non-reliable",topic="prices"} 140.0
                wsserver_bytes_published_total{namespace="/non-reliable",topic="prices"} 3200.0
                wsserver_messages_received_total{namespace="/non-reliable",topic="prices"} 110.0
                wsserver_bytes_received_total{namespace="/non-reliable",topic="prices"} 2800.0
                wsserver_messages_coalesced_total{namespace="/non-reliable",topic="prices"} 7.0
                wsserver_connections_active{namespace="/reliable"} 4.0
                wsserver_messages_published_total{namespace="/reliable",topic="alerts"} 25.0
                wsserver_bytes_published_total{namespace="/reliable",topic="alerts"} 700.0
                wsserver_messages_received_total{namespace="/reliable",topic="alerts"} 18.0
                wsserver_bytes_received_total{namespace="/reliable",topic="alerts"} 440.0
                wsserver_retry_count_total{namespace="/reliable",topic="alerts"} 3.0
                wsserver_queue_overflow_total{namespace="/reliable",topic="alerts",reason="full"} 2.0
                """, Instant.parse("2026-04-06T18:00:00Z"));

        assertThat(snapshot.activeClients()).isEqualTo(16);
        assertThat(snapshot.messagesPublished()).isEqualTo(165);
        assertThat(snapshot.bytesPublished()).isEqualTo(3900);
        assertThat(snapshot.messagesReceived()).isEqualTo(128);
        assertThat(snapshot.bytesReceived()).isEqualTo(3240);
        assertThat(snapshot.queueOverflow()).isEqualTo(2);
        assertThat(snapshot.retryCount()).isEqualTo(3);
        assertThat(snapshot.coalesced()).isEqualTo(7);
        assertThat(snapshot.namespaces()).extracting(NamespaceSnapshot::namespace)
                .containsExactly("/non-reliable", "/reliable");
        assertThat(snapshot.namespaces().getFirst().messagesReceived()).isEqualTo(110);
        assertThat(snapshot.namespaces().get(1).retryCount()).isEqualTo(3);
    }

    @Test
    void computesRatesAndTreatsCounterResetAsFreshSeries() {
        DemoMetricsSnapshot previous = PrometheusMetricsParser.parse("""
                wsserver_connections_active{namespace="/non-reliable"} 10.0
                wsserver_messages_published_total{namespace="/non-reliable",topic="prices"} 200.0
                wsserver_bytes_published_total{namespace="/non-reliable",topic="prices"} 5000.0
                wsserver_messages_received_total{namespace="/non-reliable",topic="prices"} 180.0
                wsserver_bytes_received_total{namespace="/non-reliable",topic="prices"} 4200.0
                """, Instant.parse("2026-04-06T18:00:00Z"));
        DemoMetricsSnapshot current = PrometheusMetricsParser.parse("""
                wsserver_connections_active{namespace="/non-reliable"} 14.0
                wsserver_messages_published_total{namespace="/non-reliable",topic="prices"} 40.0
                wsserver_bytes_published_total{namespace="/non-reliable",topic="prices"} 1200.0
                wsserver_messages_received_total{namespace="/non-reliable",topic="prices"} 25.0
                wsserver_bytes_received_total{namespace="/non-reliable",topic="prices"} 900.0
                """, Instant.parse("2026-04-06T18:00:05Z"));

        DashboardSnapshot snapshot = DashboardSnapshot.from("throughput", current, previous);

        assertThat(snapshot.presetId()).isEqualTo("throughput");
        assertThat(snapshot.activeClients()).isEqualTo(14);
        assertThat(snapshot.messagesPerSecond()).isEqualTo(8.0);
        assertThat(snapshot.sendBytesPerSecond()).isEqualTo(240.0);
        assertThat(snapshot.receiveBytesPerSecond()).isEqualTo(180.0);
        assertThat(snapshot.totalMessages()).isEqualTo(65);
    }

    @Test
    void parsesScientificNotationCountersUsedByLargeBulkTransfers() {
        DemoMetricsSnapshot snapshot = PrometheusMetricsParser.parse("""
                wsserver_connections_active{namespace="/bulk"} 4.0
                wsserver_messages_published_total{namespace="/bulk",topic="binary.blob"} 32.0
                wsserver_bytes_published_total{namespace="/bulk",topic="binary.blob"} 2.62144E7
                wsserver_messages_received_total{namespace="/bulk",topic="binary.blob"} 32.0
                wsserver_bytes_received_total{namespace="/bulk",topic="binary.blob"} 2.097152E7
                """, Instant.parse("2026-04-06T18:00:00Z"));

        assertThat(snapshot.activeClients()).isEqualTo(4);
        assertThat(snapshot.messagesPublished()).isEqualTo(32);
        assertThat(snapshot.bytesPublished()).isEqualTo(26_214_400L);
        assertThat(snapshot.messagesReceived()).isEqualTo(32);
        assertThat(snapshot.bytesReceived()).isEqualTo(20_971_520L);
    }
}
