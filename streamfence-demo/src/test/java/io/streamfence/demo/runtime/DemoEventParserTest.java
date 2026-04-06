package io.streamfence.demo.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DemoEventParserTest {

    @Test
    void parseRoundTripsAJsonEventLine() {
        String line = "{\"timestamp\":\"2026-04-05T20:00:00Z\",\"processType\":\"client\",\"processName\":\"price-bot\",\"direction\":\"outbound\",\"category\":\"publish\",\"namespace\":\"/non-reliable\",\"topic\":\"prices\",\"clientId\":\"c1\",\"sender\":\"price-bot\",\"receiver\":\"server\",\"summary\":\"sent sample\",\"payloadSource\":\"price-bot\",\"payloadPreview\":\"{\\\"price\\\":42}\"}";

        DemoEvent event = DemoEventParser.parse(line);

        assertThat(event.processName()).isEqualTo("price-bot");
        assertThat(event.topic()).isEqualTo("prices");
        assertThat(event.sender()).isEqualTo("price-bot");
        assertThat(event.receiver()).isEqualTo("server");
        assertThat(event.payloadSource()).isEqualTo("price-bot");
    }

    @Test
    void toJsonLineSerializesACompleteDemoEvent() {
        DemoEvent event = sampleEvent();

        String line = DemoEventWriter.toJsonLine(event);

        DemoEvent parsed = DemoEventParser.parse(line);
        assertThat(parsed).isEqualTo(event);
    }

    @Test
    void writeEmitsAJsonLineFollowedByNewline() {
        DemoEvent event = sampleEvent();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try (PrintStream printStream = new PrintStream(out, true, StandardCharsets.UTF_8)) {
            DemoEventWriter.write(printStream, event);
        }

        String output = out.toString(StandardCharsets.UTF_8);
        assertThat(output).endsWith(System.lineSeparator());
        assertThat(DemoEventParser.parse(output.strip())).isEqualTo(event);
    }

    private static DemoEvent sampleEvent() {
        return new DemoEvent(
                "2026-04-05T20:00:00Z",
                "client",
                "price-bot",
                "outbound",
                "publish",
                "/non-reliable",
                "prices",
                "c1",
                "price-bot",
                "server",
                "sent sample",
                "price-bot",
                null,
                null,
                "{\"price\":42}"
        );
    }
}
