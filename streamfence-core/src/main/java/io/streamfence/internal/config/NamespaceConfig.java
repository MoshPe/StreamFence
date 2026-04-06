package io.streamfence.internal.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NamespaceConfig(
        boolean authRequired
) {
}
