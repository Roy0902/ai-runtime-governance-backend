package local.governance.provider.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import local.governance.config.GovernanceProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Owns the deployment's asymmetric credential key pair.
 *
 * <p>The public key is all that is needed to store a provider secret. The
 * private key is required to use it, so it can live in a KMS or HSM instead of
 * the database. The local implementation persists both PEM files under
 * {@code governance.credentials.key-dir} and generates them on first start.
 */
@Component
public class CredentialKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(CredentialKeyProvider.class);
    private static final String PRIVATE_FILE = "credential-private.pem";
    private static final String PUBLIC_FILE = "credential-public.pem";

    private final String keyId;
    private final PublicKey publicKey;
    private final PrivateKey privateKey;

    @Autowired
    public CredentialKeyProvider(GovernanceProperties properties) {
        this(Path.of(properties.getCredentials().getKeyDir()));
    }

    CredentialKeyProvider(Path directory) {
        try {
            Files.createDirectories(directory);
            KeyPair pair = loadOrCreate(directory);
            this.publicKey = pair.getPublic();
            this.privateKey = pair.getPrivate();
            this.keyId = fingerprint(publicKey);
            log.info("Provider credential key {} loaded from {}", keyId, directory.toAbsolutePath());
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException("Could not initialize provider credential keys", exception);
        }
    }

    public String keyId() {
        return keyId;
    }

    public PublicKey publicKey() {
        return publicKey;
    }

    public PrivateKey privateKey() {
        return privateKey;
    }

    private static KeyPair loadOrCreate(Path directory) throws IOException, GeneralSecurityException {
        Path privatePath = directory.resolve(PRIVATE_FILE);
        Path publicPath = directory.resolve(PUBLIC_FILE);
        KeyFactory factory = KeyFactory.getInstance("RSA");

        if (Files.exists(privatePath)) {
            PrivateKey privateKey = factory.generatePrivate(
                    new PKCS8EncodedKeySpec(decode(Files.readString(privatePath, StandardCharsets.US_ASCII))));
            PublicKey publicKey;
            if (Files.exists(publicPath)) {
                publicKey = factory.generatePublic(
                        new X509EncodedKeySpec(decode(Files.readString(publicPath, StandardCharsets.US_ASCII))));
            } else {
                RSAPrivateCrtKey crt = (RSAPrivateCrtKey) privateKey;
                publicKey = factory.generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
                write(publicPath, encode("PUBLIC KEY", publicKey.getEncoded()), false);
            }
            return new KeyPair(publicKey, privateKey);
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();
        write(privatePath, encode("PRIVATE KEY", pair.getPrivate().getEncoded()), true);
        write(publicPath, encode("PUBLIC KEY", pair.getPublic().getEncoded()), false);
        return pair;
    }

    private static void write(Path path, String pem, boolean ownerOnly) throws IOException {
        Files.writeString(path, pem, StandardCharsets.US_ASCII);
        if (ownerOnly) {
            try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows: inherited user-profile ACLs apply.
            }
        }
    }

    private static String encode(String type, byte[] value) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(value)
                + "\n-----END " + type + "-----\n";
    }

    private static byte[] decode(String pem) {
        String body = pem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private static String fingerprint(PublicKey publicKey) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded());
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < 8; index += 1) {
            builder.append(String.format("%02x", digest[index]));
        }
        return builder.toString();
    }
}
