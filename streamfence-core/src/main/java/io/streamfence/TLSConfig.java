package io.streamfence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * TLS/SSL configuration for a {@link TransportMode#WSS} server.
 *
 * <p>Paths are resolved at server start time. When a {@link TLSConfig} is present, the server
 * loads the PEM-encoded certificate chain and private key and wraps them in a PKCS12 key store.
 *
 * @param certChainPemPath    path to the PEM-encoded certificate chain file
 * @param privateKeyPemPath   path to the PEM-encoded private key file
 * @param privateKeyPassword  passphrase for an encrypted private key, or {@code null} if
 *                            the key is not encrypted
 * @param keyStorePassword    passphrase used to protect the generated PKCS12 key store
 * @param protocol            the TLS protocol version (default {@code "TLSv1.3"})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TLSConfig(
        String certChainPemPath,
        String privateKeyPemPath,
        String privateKeyPassword,
        String keyStorePassword,
        String protocol
) {
    /**
     * Compact constructor that defaults {@code protocol} to {@code "TLSv1.3"} when not set.
     */
    public TLSConfig {
        if (protocol == null || protocol.isBlank()) {
            protocol = "TLSv1.3";
        }
    }

    /**
     * Convenience constructor that uses {@code "TLSv1.3"} as the protocol.
     *
     * @param certChainPemPath   path to the PEM-encoded certificate chain file
     * @param privateKeyPemPath  path to the PEM-encoded private key file
     * @param privateKeyPassword passphrase for the private key, or {@code null}
     * @param keyStorePassword   passphrase for the generated PKCS12 key store
     */
    public TLSConfig(String certChainPemPath, String privateKeyPemPath, String privateKeyPassword, String keyStorePassword) {
        this(certChainPemPath, privateKeyPemPath, privateKeyPassword, keyStorePassword, "TLSv1.3");
    }
}
