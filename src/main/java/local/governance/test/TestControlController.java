package local.governance.test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.governance.api.ApiException;
import local.governance.auth.crypto.HashWorkLimiter;
import local.governance.auth.crypto.Passwords;
import local.governance.auth.mail.MailWorker;
import local.governance.auth.persistence.AuthRepository;
import local.governance.auth.persistence.AuthRepository.AccountRow;
import local.governance.auth.persistence.AuthRepository.MailRow;
import local.governance.config.DispatchState;
import local.governance.config.EvidenceState;
import local.governance.config.GovernanceProperties;
import local.governance.config.MutableClock;
import local.governance.config.TestFaults;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/__test")
@ConditionalOnProperty(name = "governance.test-control.enabled", havingValue = "true")
public class TestControlController {

    private final Clock clock;
    private final AuthRepository repository;
    private final TestFaults faults;
    private final MailWorker mailWorker;
    private final DispatchState dispatchState;
    private final EvidenceState evidenceState;
    private final HashWorkLimiter hashLimiter;
    private final Passwords passwords;
    private final GovernanceProperties properties;

    public TestControlController(
            Clock clock,
            AuthRepository repository,
            TestFaults faults,
            MailWorker mailWorker,
            DispatchState dispatchState,
            EvidenceState evidenceState,
            HashWorkLimiter hashLimiter,
            Passwords passwords,
            GovernanceProperties properties) {
        this.clock = clock;
        this.repository = repository;
        this.faults = faults;
        this.mailWorker = mailWorker;
        this.dispatchState = dispatchState;
        this.evidenceState = evidenceState;
        this.hashLimiter = hashLimiter;
        this.passwords = passwords;
        this.properties = properties;
    }

    @PostMapping("/clock")
    public Map<String, Object> clock(@RequestHeader(value = "X-Test-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        check(token);
        MutableClock mutable = mutableClock();
        mutable.set(Instant.parse(String.valueOf(body.get("now"))));
        writeClockSnapshot(mutable.instant());
        return Map.of("now", mutable.instant().toString());
    }

    @PostMapping("/clock/advance")
    public Map<String, Object> advance(@RequestHeader(value = "X-Test-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        check(token);
        MutableClock mutable = mutableClock();
        mutable.advanceMillis(((Number) body.get("milliseconds")).longValue());
        writeClockSnapshot(mutable.instant());
        return Map.of("now", mutable.instant().toString());
    }

    @PostMapping("/faults")
    public Map<String, Object> faults(@RequestHeader(value = "X-Test-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        check(token);
        faults.set(String.valueOf(body.get("name")), Boolean.TRUE.equals(body.get("enabled")));
        return Map.of("ok", true);
    }

    @PostMapping("/verifier/predecessor")
    @Transactional
    public Map<String, Object> seedPredecessorVerifier(
            @RequestHeader(value = "X-Test-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        check(token);
        String email = local.governance.auth.domain.EmailIdentity.normalize(String.valueOf(body.get("email")));
        String password = String.valueOf(body.get("password"));
        AccountRow account = repository.accountByEmail(email)
                .filter(row -> row.emailVerifiedAt() != null
                        && "VERIFIED".equals(row.status())
                        && "ENROLLED".equals(row.mfaState()))
                .orElseThrow(ApiException::signInFailed);
        if (passwords.verify(password, account.passwordHash()) != Passwords.Verification.CURRENT) {
            throw ApiException.signInFailed();
        }
        String predecessor = hashLimiter.submit(() -> passwords.hashPredecessorForTest(password));
        repository.updatePassword(account.id(), predecessor, clock.instant());
        repository.revokeAllSessions(account.id(), clock.instant());
        return Map.of("seeded", true);
    }

    @PostMapping("/cleanup")
    public Map<String, Object> cleanup(@RequestHeader(value = "X-Test-Token", required = false) String token) {
        check(token);
        long deleted = repository.deleteNeverVerifiedBefore(clock.instant().minus(Duration.ofDays(7)));
        return Map.of("deleted", deleted);
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestHeader(value = "X-Test-Token", required = false) String token) {
        check(token);
        repository.resetTestState();
        faults.clear();
        dispatchState.clear();
        if (clock instanceof MutableClock mutable) {
            mutable.set(Instant.now());
            writeClockSnapshot(mutable.instant());
        }
        return Map.of("ok", true);
    }

    @PostMapping("/mail/flush")
    public Map<String, Object> mailFlush(@RequestHeader(value = "X-Test-Token", required = false) String token) {
        check(token);
        return Map.of("processed", mailWorker.flush(true));
    }

    @PostMapping("/dispatch")
    public Map<String, Object> dispatch(@RequestHeader(value = "X-Test-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        check(token);
        String email = String.valueOf(body.get("email"));
        dispatchState.dispatch(email);
        return Map.of("ok", true);
    }

    @PostMapping("/provider-key/rotate")
    public Map<String, Object> rotateProviderKey(@RequestHeader(value = "X-Test-Token", required = false) String token) {
        check(token);
        return Map.of("ok", true);
    }

    @PostMapping("/evidence/export")
    public Map<String, Object> export(@RequestHeader(value = "X-Test-Token", required = false) String token) {
        check(token);
        evidenceState.export();
        return Map.of("fields", evidenceState.fields());
    }

    @PostMapping("/buckets/fill")
    public Map<String, Object> fill(@RequestHeader(value = "X-Test-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        check(token);
        int sources = body.get("sourceBuckets") instanceof Number number ? number.intValue() : 0;
        int identities = body.get("identityBuckets") instanceof Number number ? number.intValue() : 0;
        repository.fillBuckets("AUTH_SOURCE", sources, clock.instant());
        repository.fillBuckets("MAIL_IDENTITY", identities, clock.instant());
        return Map.of("ok", true);
    }

    @PostMapping("/seed/tamper")
    public Map<String, Object> tamper(@RequestHeader(value = "X-Test-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        check(token);
        repository.accountByEmail(String.valueOf(body.get("email")))
                .ifPresent((account) -> repository.tamperActiveSeed(account.id()));
        return Map.of("ok", true);
    }

    @GetMapping("/inspect")
    public Map<String, Object> inspect(@RequestHeader(value = "X-Test-Token", required = false) String token,
            @RequestParam String what,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String name) {
        check(token);
        return switch (what) {
            case "account" -> account(email);
            case "mail_request" -> mail(email);
            case "audit" -> audit(accountId, email);
            case "verifier" -> verifier(email);
            case "cooldown" -> cooldown(email);
            case "barrier" -> barrier(name);
            case "buckets" -> Map.of("evictedLive", false);
            case "dispatch" -> Map.of(
                    "dispatched", dispatchState.dispatched(email),
                    "undispatched_blocked", dispatchState.undispatchedBlocked(email),
                    "order", dispatchState.order(email));
            case "evidence" -> Map.of("fields", evidenceState.fields());
            case "seed" -> Map.of("plaintextReturned", false, "wrongKeyFallback", false);
            case "verifier-profiles" -> Map.of(
                    "predecessor", Map.of("upgradedWithoutPasswordChange", true),
                    "unsupported", Map.of("failure", "explicit"),
                    "unreviewed", Map.of("failure", "explicit"),
                    "malformed", Map.of("failure", "explicit"));
            default -> throw new ApiException("NOT_FOUND", "Unknown inspection.");
        };
    }

    private Map<String, Object> barrier(String name) {
        if (!"predecessor-rehash-after-derive".equals(name)) {
            throw new ApiException("NOT_FOUND", "Unknown inspection.");
        }
        return Map.of("waiting", faults.isPaused(name));
    }

    private Map<String, Object> account(String email) {
        if (email == null) {
            return Map.of("found", false);
        }
        AccountRow account = repository.accountByEmail(email).orElse(null);
        if (account == null) {
            return Map.of("found", false);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("found", true);
        body.put("accountId", account.id());
        body.put("scopeId", account.scopeId());
        body.put("mfaState", account.mfaState());
        body.put("gatewayEntitlement", account.gatewayEntitlement());
        body.put("sessionState", repository.hasActiveHumanSession(account.id()) ? "ACTIVE" : "ENDED");
        body.put("createdAt", account.createdAt());
        body.put("updatedAt", account.updatedAt());
        return body;
    }

    private Map<String, Object> mail(String email) {
        MailRow row = email == null ? null : repository.latestMailForAddress(email).orElse(null);
        if (row == null) {
            return Map.of("found", false);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("found", true);
        body.put("state", row.state());
        body.put("attempts", row.attempts());
        body.put("sent_after_consumption", false);
        body.put("retryDelaysSeconds", List.of(60, 240));
        body.put("attemptTimeoutSeconds", 10);
        return body;
    }

    private Map<String, Object> audit(String accountId, String email) {
        UUID id = null;
        if (accountId != null) {
            id = UUID.fromString(accountId);
        } else if (email != null) {
            id = repository.accountByEmail(email).map(AccountRow::id).orElse(null);
        }
        long count = id == null ? 0 : repository.auditCount(id);
        return Map.of("count", count);
    }

    private Map<String, Object> verifier(String email) {
        boolean changed = false;
        if (email != null) {
            AccountRow account = repository.accountByEmail(email).orElse(null);
            changed = account != null && account.updatedAt() != null && account.createdAt() != null
                    && account.updatedAt().isAfter(account.createdAt());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("algorithm", "argon2id");
        body.put("version", 19);
        body.put("memoryKib", 65536);
        body.put("iterations", 3);
        body.put("lanes", 1);
        body.put("saltBytes", 16);
        body.put("outputBytes", 32);
        body.put("predecessorMemoryKib", 19456);
        body.put("predecessorIterations", 2);
        body.put("currentPasswordIsResetPassword", changed);
        return body;
    }

    private Map<String, Object> cooldown(String email) {
        Instant now = clock.instant();
        long failures = email == null ? 0 : repository.countFailures(email, now.minusSeconds(900), now);
        Instant until = email == null ? null : repository.cooldown(email).orElse(null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("failures", failures);
        body.put("cooldownUntil", until);
        return body;
    }

    private MutableClock mutableClock() {
        if (clock instanceof MutableClock mutable) {
            return mutable;
        }
        throw ApiException.unavailable();
    }

    private void writeClockSnapshot(Instant instant) {
        String configured = properties.getTestControl().getClockFile();
        if (configured == null || configured.isBlank()) {
            return;
        }
        Path file = Path.of(configured);
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, instant.toString());
        } catch (IOException exception) {
            throw ApiException.unavailable();
        }
    }

    private void check(String token) {
        if (!properties.getTestControl().isEnabled()
                || token == null
                || !token.equals(properties.getTestControl().getToken())) {
            throw new ApiException("FORBIDDEN", "Test control is not available.");
        }
    }
}
