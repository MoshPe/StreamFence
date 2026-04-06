package io.streamfence.demo.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DemoControlCommandTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void targetedSampleRoundTripsThroughJson() {
        DemoControlCommand command = DemoControlCommand.targetedSample(
                "/non-reliable",
                "client-42",
                "prices",
                MAPPER.valueToTree(Map.of("symbol", "WSR", "price", 42.75)));

        DemoControlCommand parsed = DemoControlCommand.fromJson(command.toJson());

        assertThat(parsed.type()).isEqualTo("targetedSample");
        assertThat(parsed.namespace()).isEqualTo("/non-reliable");
        assertThat(parsed.clientId()).isEqualTo("client-42");
        assertThat(parsed.topic()).isEqualTo("prices");
        assertThat(parsed.payload().get("symbol").asText()).isEqualTo("WSR");
    }

    @Test
    void pauseCommandSerializesWithoutRoutingFields() {
        DemoControlCommand parsed = DemoControlCommand.fromJson(DemoControlCommand.pause().toJson());

        assertThat(parsed.type()).isEqualTo("pause");
        assertThat(parsed.namespace()).isNull();
        assertThat(parsed.topic()).isNull();
        assertThat(parsed.clientId()).isNull();
        assertThat(parsed.payload()).isNull();
    }
}
