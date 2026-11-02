package local.governance.auth.mail;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import local.governance.auth.crypto.SeedCipher;
import local.governance.auth.persistence.AuthRepository;
import local.governance.auth.persistence.AuthRepository.AccountRow;
import local.governance.auth.persistence.AuthRepository.LinkRow;
import local.governance.auth.persistence.AuthRepository.MailRow;
import local.governance.config.GovernanceProperties;
import local.governance.config.TestFaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MailWorker {

    private static final Logger log = LoggerFactory.getLogger(MailWorker.class);

    private final AuthRepository repository;
    private final SeedCipher cipher;
    private final JavaMailSender sender;
    private final GovernanceProperties properties;
    private final TestFaults faults;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    public MailWorker(AuthRepository repository, SeedCipher cipher, JavaMailSender sender,
            GovernanceProperties properties, TestFaults faults, Clock clock) {
        this.repository = repository;
        this.cipher = cipher;
        this.sender = sender;
        this.properties = properties;
        this.faults = faults;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 1000L, initialDelay = 2000L)
    public void scheduled() {
        flush(false);
    }

    /** Test control: optionally accelerate retry delays until the queue is idle. */
    public int flush(boolean accelerated) {
        if (!lock.tryLock()) {
            return 0;
        }
        try {
            int processed = 0;
            for (int round = 0; round < 10; round += 1) {
                List<MailRow> due = repository.dueMail(10, clock.instant());
                if (due.isEmpty()) {
                    break;
                }
                for (MailRow row : due) {
                    if (process(row, accelerated)) {
                        processed += 1;
                    }
                }
            }
            return processed;
        } finally {
            lock.unlock();
        }
    }

    private boolean process(MailRow row, boolean accelerated) {
        Optional<MailRow> locked = repository.lockMail(row.id());
        if (locked.isEmpty()) {
            return false;
        }
        MailRow current = locked.get();
        if (!"PENDING".equals(current.state()) && !"DELAYED".equals(current.state())) {
            return false;
        }
        if (current.linkId() == null) {
            repository.updateMail(current.id(), "CANCELLED", current.attempts(), clock.instant(), "no-link", clock.instant());
            return true;
        }
        Optional<LinkRow> maybeLink = repository.linkById(current.linkId());
        Instant now = clock.instant();
        if (maybeLink.isEmpty() || maybeLink.get().consumedAt() != null || maybeLink.get().supersededAt() != null
                || !now.isBefore(maybeLink.get().expiresAt())) {
            repository.updateMail(current.id(), "SUPERSEDED", current.attempts(), now, "link-not-current", now);
            return true;
        }
        LinkRow link = maybeLink.get();
        Optional<AccountRow> maybeAccount = current.accountId() == null
                ? Optional.empty()
                : repository.accountById(current.accountId());
        if (maybeAccount.isEmpty() || "DISABLED".equals(maybeAccount.get().status())) {
            repository.updateMail(current.id(), "CANCELLED", current.attempts(), now, "account-not-eligible", now);
            return true;
        }

        try {
            if (faults.enabled("smtp")) {
                throw new IllegalStateException("SMTP fault enabled");
            }
            send(current, link);
            repository.updateMail(current.id(), "SENT", current.attempts() + 1, now, null, now);
            repository.clearMailToken(current.id());
            return true;
        } catch (RuntimeException exception) {
            int attempts = current.attempts() + 1;
            log.warn("mail delivery failed attempt {}", attempts);
            if (attempts >= 3) {
                repository.updateMail(current.id(), "FAILED_FINAL", attempts, now, exception.getClass().getSimpleName(), now);
            } else {
                Instant next = accelerated ? now : now.plus(Duration.ofSeconds(attempts == 1 ? 60 : 240));
                repository.updateMail(current.id(), "DELAYED", attempts, next, exception.getClass().getSimpleName(), now);
            }
            return true;
        }
    }

    private void send(MailRow row, LinkRow link) {
        if (row.tokenCiphertext() == null || row.tokenNonce() == null || row.tokenKeyId() == null) {
            throw new IllegalStateException("mail token is unavailable");
        }
        String token = cipher.decrypt(row.accountId(), link.id(), row.tokenKeyId(), row.tokenNonce(), row.tokenCiphertext());
        String path = "RESET".equals(row.purpose()) ? "/reset-password?token=" : "/verify?token=";
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(row.address());
        message.setSubject("RESET".equals(row.purpose())
                ? "Reset your AI Runtime Governance password"
                : "Verify your AI Runtime Governance email");
        message.setText("Open this single-use link:\n" + properties.getBaseUrl() + path + token
                + "\n\nThis link expires automatically. If you did not request it, ignore this message.");
        sender.send(message);
    }
}
