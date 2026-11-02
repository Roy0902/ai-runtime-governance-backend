package local.governance.auth.mail;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import local.governance.api.ApiException;
import local.governance.auth.crypto.SeedCipher;
import local.governance.auth.crypto.Tokens;
import local.governance.auth.persistence.AuthRepository;
import local.governance.auth.persistence.AuthRepository.LinkRow;
import local.governance.auth.persistence.AuthRepository.MailRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MailService {

    private static final int MAX_UNCOMPLETED = 100;

    private final AuthRepository repository;
    private final Tokens tokens;
    private final SeedCipher cipher;
    private final Clock clock;
    private final local.governance.config.TestFaults faults;

    public MailService(AuthRepository repository, Tokens tokens, SeedCipher cipher, Clock clock,
            local.governance.config.TestFaults faults) {
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
            throw ApiException.unavailable();
        }
        if (repository.uncompletedMailCount() >= MAX_UNCOMPLETED) {
            throw ApiException.unavailable();
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
