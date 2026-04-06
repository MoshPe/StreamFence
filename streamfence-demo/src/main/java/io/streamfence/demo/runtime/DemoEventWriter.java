package io.streamfence.demo.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintStream;

public final class DemoEventWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DemoEventWriter() {
    }

    public static String toJsonLine(DemoEvent event) {
        try {
            return MAPPER.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize demo event", exception);
        }
    }

    public static void write(PrintStream out, DemoEvent event) {
        out.println(toJsonLine(event));
    }
}
