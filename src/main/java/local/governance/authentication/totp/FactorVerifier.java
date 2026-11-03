package local.governance.authentication.totp;

import java.time.Clock;
import java.util.OptionalLong;
import local.governance.authentication.api.request.AuthenticationFactor;
import local.governance.api.APIException;
import local.governance.authentication.crypto.RecoveryCodeService;
import local.governance.authentication.crypto.SeedCipher;
import local.governance.authentication.crypto.TOTPService;
import local.governance.authentication.persistence.AuthenticationRepository;
import local.governance.authentication.persistence.AuthenticationRepository.AccountRow;
import local.governance.authentication.persistence.AuthenticationRepository.RecoveryCodeRow;
import local.governance.authentication.persistence.AuthenticationRepository.RecoverySetRow;
import local.governance.authentication.persistence.AuthenticationRepository.SeedRow;
import org.springframework.stereotype.Service;

@Service
public class FactorVerifier {

    private final AuthenticationRepository repository;
    private final TOTPService totp;
    private final RecoveryCodeService recoveryCodes;
    private final SeedCipher cipher;
    private final Clock clock;
    private final local.governance.config.TestFaultRegistry faults;

    public FactorVerifier(AuthenticationRepository repository, TOTPService totp, RecoveryCodeService recoveryCodes, SeedCipher cipher,
            Clock clock, local.governance.config.TestFaultRegistry faults) {
        this.repository = repository;
        this.totp = totp;
        this.recoveryCodes = recoveryCodes;
        this.cipher = cipher;
        this.clock = clock;
        this.faults = faults;
    }

    public void verify(AccountRow account, AuthenticationFactor factor) {
        if (factor == null || factor.kind() == null) {
            throw APIException.signInFailed();
        }
        if ("TOTP".equals(factor.kind())) {
            verifyTotp(account, factor.code());
            return;
        }
        if ("RECOVERY".equals(factor.kind())) {
            verifyRecovery(account, factor.code());
            return;
        }
        throw APIException.signInFailed();
    }

    public void verifyTotp(AccountRow account, String code) {
        SeedRow seed = repository.activeSeed(account.id()).orElseThrow(APIException::signInFailed);
        String secret;
        try {
            secret = cipher.decrypt(account.id(), seed.id(), seed.keyId(), seed.nonce(), seed.ciphertext());
        } catch (SeedCipher.KeyUnavailableException exception) {
            throw APIException.unavailable();
        }
        OptionalLong step = totp.verify(secret, code, clock.instant());
        if (step.isEmpty()) {
            throw APIException.signInFailed();
        }
        if (!repository.recordTotpStep(account.id(), step.getAsLong(), clock.instant())) {
            throw APIException.signInFailed();
        }
    }

    public void verifyRecovery(AccountRow account, String code) {
        if (faults.enabled("recovery-verifier")) {
            throw APIException.unavailable();
        }
        RecoverySetRow set = repository.activeRecoverySet(account.id()).orElseThrow(APIException::signInFailed);
        String canonical = recoveryCodes.normalize(code);
        if (canonical == null) {
            throw APIException.signInFailed();
        }
        for (RecoveryCodeRow candidate : repository.recoveryCodes(set.id())) {
            if (recoveryCodes.matches(canonical, candidate.verifier(), account.id(), set.id())) {
                if (candidate.consumedAt() != null || !repository.consumeRecoveryCode(candidate.id(), clock.instant())) {
                    throw APIException.signInFailed();
                }
                return;
            }
        }
        throw APIException.signInFailed();
    }

    public String decryptSeed(AccountRow account, SeedRow seed) {
        try {
            return cipher.decrypt(account.id(), seed.id(), seed.keyId(), seed.nonce(), seed.ciphertext());
        } catch (SeedCipher.KeyUnavailableException exception) {
            throw APIException.unavailable();
        }
    }
}
