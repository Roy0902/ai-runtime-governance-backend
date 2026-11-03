package local.governance.authentication.mail;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import local.governance.api.APIException;
import local.governance.authentication.crypto.SeedCipher;
import local.governance.authentication.crypto.SecureTokenService;
import local.governance.authentication.persistence.AuthenticationRepository;
import local.governance.authentication.persistence.AuthenticationRepository.LinkRow;
import local.governance.authentication.persistence.AuthenticationRepository.MailRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MailService {

    private static final int MAX_UNCOMPLETED = 100;

    private final AuthenticationRepository repository;
    private final SecureTokenService tokens;
    private final SeedCipher cipher;
    private final Clock clock;
    private final local.governance.config.TestFaultRegistry faults;

    public MailService(AuthenticationRepository repository, SecureTokenService tokens, SeedCipher cipher, Clock clock,
            local.governance.config.TestFaultRegistry faults) {
        this.repository = repository;
        this.tokens = tokens;
        this.cipher = cipher;
        this.clock = clock;
        this.faults = faults;
    }

    @Transactional
    public void issueVerification(UUID accountId, String email) {
        issue(accountId, email, "VERIFY", Duration.ofHours(24));
    }

    @Transactional
    public void issueReset(UUID accountId, String email) {
        issue(accountId, email, "RESET", Duration.ofMinutes(30));
    }

    private void issue(UUID accountId, String email, String purpose, Duration lifetime) {
        if (faults.enabled("mail-admission")) {
            throw APIException.unavailable();
        }
        if (repository.uncompletedMailCount() >= MAX_UNCOMPLETED) {
            throw APIException.unavailable();
        }
        Instant now = clock.instant();
        repository.supersedeCurrentLinks(accountId, purpose, now);
        String token = tokens.issue();
        UUID linkId = UUID.randomUUID();
        LinkRow link = new LinkRow(
                linkId,
                accountId,
                purpose,
                tokens.hash(token),
                now,
                now.plus(lifetime),
                null,
                null);
        repository.insertLink(link);
        SeedCipher.Encrypted encrypted = cipher.encrypt(accountId, linkId, token);
        repository.insertMail(new MailRow(
                UUID.randomUUID(),
                accountId,
                email,
                purpose,
                linkId,
                "PENDING",
                0,
                now,
                encrypted.nonce(),
                encrypted.ciphertext(),
                encrypted.keyId(),
                null));
    }

    public long uncompleted() {
        return repository.uncompletedMailCount();
    }
}
