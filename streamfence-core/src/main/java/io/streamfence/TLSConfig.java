package io.streamfence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TLSConfig(
        String certChainPemPath,
        String privateKeyPemPath,
        String privateKeyPassword,
        String keyStorePassword,
        String protocol
) {
    public TLSConfig {
        if (protocol == null || protocol.isBlank()) {
            protocol = "TLSv1.3";
        }
    }

    public TLSConfig(String certChainPemPath, String privateKeyPemPath, String privateKeyPassword, String keyStorePassword) {
        this(certChainPemPath, privateKeyPemPath, privateKeyPassword, keyStorePassword, "TLSv1.3");
    }
}
