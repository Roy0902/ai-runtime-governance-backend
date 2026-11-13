package local.governance.provider.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialCryptoTest {

    @TempDir
    Path directory;

    @Test
    void sealsAndOpensWithTheDeploymentKey() {
        CredentialKeyProvider keys = new CredentialKeyProvider(directory);
        EnvelopeCipher cipher = new EnvelopeCipher(keys);
        String account = "user:" + UUID.randomUUID();

        EnvelopeCipher.Sealed sealed = cipher.seal(account, "LANGFUSE", "sk-lf-very-secret");

        assertEquals("sk-lf-very-secret", cipher.open(account, "LANGFUSE", sealed));
        assertEquals(keys.keyId(), sealed.keyId());
    }

    @Test
    void keepsTheSameKeyAcrossRestarts() {
        CredentialKeyProvider first = new CredentialKeyProvider(directory);
        EnvelopeCipher cipher = new EnvelopeCipher(first);
        String account = "group:" + UUID.randomUUID();
        EnvelopeCipher.Sealed sealed = cipher.seal(account, "LANGSMITH", "lsv2_pt_secret");

        CredentialKeyProvider second = new CredentialKeyProvider(directory);

        assertEquals(first.keyId(), second.keyId());
        assertEquals("lsv2_pt_secret", new EnvelopeCipher(second).open(account, "LANGSMITH", sealed));
    }

    @Test
    void refusesToMoveASealedSecretToAnotherAccountOrProvider() {
        CredentialKeyProvider keys = new CredentialKeyProvider(directory);
        EnvelopeCipher cipher = new EnvelopeCipher(keys);
        String account = "user:" + UUID.randomUUID();
        EnvelopeCipher.Sealed sealed = cipher.seal(account, "LANGFUSE", "sk-lf-secret");

        assertThrows(IllegalStateException.class, () -> cipher.open("user:" + UUID.randomUUID(), "LANGFUSE", sealed));
        assertThrows(IllegalStateException.class, () -> cipher.open(account, "LANGSMITH", sealed));
    }

    @Test
    void producesDifferentCiphertextForTheSameSecret() {
        CredentialKeyProvider keys = new CredentialKeyProvider(directory);
        EnvelopeCipher cipher = new EnvelopeCipher(keys);
        String account = "user:" + UUID.randomUUID();

        EnvelopeCipher.Sealed first = cipher.seal(account, "LANGFUSE", "sk-lf-secret");
        EnvelopeCipher.Sealed second = cipher.seal(account, "LANGFUSE", "sk-lf-secret");

        assertNotEquals(new String(first.ciphertext()), new String(second.ciphertext()));
    }
}
