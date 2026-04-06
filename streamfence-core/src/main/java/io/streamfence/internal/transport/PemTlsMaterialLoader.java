package io.streamfence.internal.transport;

import io.streamfence.TLSConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;
import org.bouncycastle.operator.InputDecryptorProvider;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;

public final class PemTlsMaterialLoader {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public LoadedKeyStore load(TLSConfig tlsConfig) {
        try {
            X509Certificate[] certificateChain = readCertificateChain(Path.of(tlsConfig.certChainPemPath()));
            PrivateKey privateKey = readPrivateKey(Path.of(tlsConfig.privateKeyPemPath()), tlsConfig.privateKeyPassword());
            char[] password = tlsConfig.keyStorePassword().toCharArray();

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, password);
            keyStore.setKeyEntry("wsserver", privateKey, password, certificateChain);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            keyStore.store(outputStream, password);
            return new LoadedKeyStore(outputStream.toByteArray(), tlsConfig.keyStorePassword());
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to load PEM TLS material", exception);
        }
    }

    private X509Certificate[] readCertificateChain(Path path) throws Exception {
        try (var inputStream = Files.newInputStream(path)) {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            Collection<? extends java.security.cert.Certificate> certificates = certificateFactory.generateCertificates(inputStream);
            return certificates.stream()
                    .map(X509Certificate.class::cast)
                    .toArray(X509Certificate[]::new);
        }
    }

    private PrivateKey readPrivateKey(Path path, String password) throws Exception {
        try (Reader reader = Files.newBufferedReader(path); PEMParser parser = new PEMParser(reader)) {
            Object object = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME);

            if (object instanceof PEMEncryptedKeyPair encryptedKeyPair) {
                requirePassword(password);
                return converter.getKeyPair(encryptedKeyPair.decryptKeyPair(new JcePEMDecryptorProviderBuilder().build(password.toCharArray()))).getPrivate();
            }
            if (object instanceof PEMKeyPair keyPair) {
                return converter.getKeyPair(keyPair).getPrivate();
            }
            if (object instanceof PKCS8EncryptedPrivateKeyInfo encryptedPrivateKeyInfo) {
                requirePassword(password);
                InputDecryptorProvider decryptorProvider = new JceOpenSSLPKCS8DecryptorProviderBuilder().build(password.toCharArray());
                return converter.getPrivateKey(encryptedPrivateKeyInfo.decryptPrivateKeyInfo(decryptorProvider));
            }
            if (object instanceof PrivateKeyInfo privateKeyInfo) {
                return converter.getPrivateKey(privateKeyInfo);
            }
            throw new IllegalArgumentException("Unsupported PEM private key format");
        }
    }

    private void requirePassword(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Encrypted PEM key requires privateKeyPassword");
        }
    }

    public record LoadedKeyStore(byte[] bytes, String password) {
        public ByteArrayInputStream inputStream() {
            return new ByteArrayInputStream(bytes);
        }
    }
}
