package local.governance.auth.totp;

import java.time.Clock;
import java.util.OptionalLong;
import local.governance.api.ApiDtos.Factor;
import local.governance.api.ApiException;
import local.governance.auth.crypto.RecoveryCodes;
import local.governance.auth.crypto.SeedCipher;
import local.governance.auth.crypto.Totp;
import local.governance.auth.persistence.AuthRepository;
import local.governance.auth.persistence.AuthRepository.AccountRow;
import local.governance.auth.persistence.AuthRepository.RecoveryCodeRow;
import local.governance.auth.persistence.AuthRepository.RecoverySetRow;
import local.governance.auth.persistence.AuthRepository.SeedRow;
import org.springframework.stereotype.Service;

@Service
public class FactorVerifier {

    private final AuthRepository repository;
    private final Totp totp;
    private final RecoveryCodes recoveryCodes;
    private final SeedCipher cipher;
    private final Clock clock;
    private final local.governance.config.TestFaults faults;

    public FactorVerifier(AuthRepository repository, Totp totp, RecoveryCodes recoveryCodes, SeedCipher cipher,
            Clock clock, local.governance.config.TestFaults faults) {
        this.repository = repository;
        this.totp = totp;
        this.recoveryCodes = recoveryCodes;
        this.cipher = cipher;
        this.clock = clock;
        this.faults = faults;
    }

    public void verify(AccountRow account, Factor factor) {
        if (factor == null || factor.kind() == null) {
            throw ApiException.signInFailed();
        }
        if ("TOTP".equals(factor.kind())) {
            verifyTotp(account, factor.code());
            return;
        }
        if ("RECOVERY".equals(factor.kind())) {
            verifyRecovery(account, factor.code());
            return;
        }
        throw ApiException.signInFailed();
    }

    public void verifyTotp(AccountRow account, String code) {
        SeedRow seed = repository.activeSeed(account.id()).orElseThrow(ApiException::signInFailed);
        String secret;
        try {
            secret = cipher.decrypt(account.id(), seed.id(), seed.keyId(), seed.nonce(), seed.ciphertext());
        } catch (SeedCipher.KeyUnavailableException exception) {
            throw ApiException.unavailable();
        }
        OptionalLong step = totp.verify(secret, code, clock.instant());
        if (step.isEmpty()) {
            throw ApiException.signInFailed();
        }
        if (!repository.recordTotpStep(account.id(), step.getAsLong(), clock.instant())) {
            throw ApiException.signInFailed();
        }
    }

    public void verifyRecovery(AccountRow account, String code) {
        if (faults.enabled("recovery-verifier")) {
            throw ApiException.unavailable();
        }
        RecoverySetRow set = repository.activeRecoverySet(account.id()).orElseThrow(ApiException::signInFailed);
        String canonical = recoveryCodes.normalize(code);
        if (canonical == null) {
            throw ApiException.signInFailed();
        }
        for (RecoveryCodeRow candidate : repository.recoveryCodes(set.id())) {
            if (recoveryCodes.matches(canonical, candidate.verifier(), account.id(), set.id())) {
                if (candidate.consumedAt() != null || !repository.consumeRecoveryCode(candidate.id(), clock.instant())) {
                    throw ApiException.signInFailed();
                }
                return;
            }
        }
        throw ApiException.signInFailed();
    }

    public String decryptSeed(AccountRow account, SeedRow seed) {
        try {
            return cipher.decrypt(account.id(), seed.id(), seed.keyId(), seed.nonce(), seed.ciphertext());
        } catch (SeedCipher.KeyUnavailableException exception) {
            throw ApiException.unavailable();
        }
    }
}
