package io.streamfence.demo.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class DemoEventParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DemoEventParser() {
    }

    public static DemoEvent parse(String line) {
        try {
            return MAPPER.readValue(line, DemoEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to parse demo event line", exception);
        }
    }
}
