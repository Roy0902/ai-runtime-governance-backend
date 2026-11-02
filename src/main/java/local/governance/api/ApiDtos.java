package local.governance.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ApiDtos {

    private ApiDtos() {}

    public record SessionDto(Instant createdAt, Instant absoluteExpiresAt, Instant idleExpiresAt, Instant recentAuthExpiresAt) {}

    public record AccountDto(
            UUID accountId,
            String email,
            boolean emailVerified,
            String mfaState,
            UUID scopeId,
            String gatewayEntitlement,
            SessionDto session) {}

    public record ContextDto(
            String state,
            String csrfToken,
            Instant setupExpiresAt,
            String enrollmentPurpose,
            AccountDto account) {}

    public record OperationDto(UUID operationId, String purpose, String state, Instant createdAt, Instant expiresAt) {}

    public record AuthenticatorSecretsDto(
            OperationDto operation,
            String manualKey,
            String provisioningUri,
            List<String> recoveryCodes) {}

    public record RecoverySecretsDto(OperationDto operation, List<String> recoveryCodes) {}

    public record MailAcceptedDto(String message) {}

    public record RegistrationBody(String email, String password) {}

    public record EmailBody(String email) {}

    public record TokenBody(String token) {}

    public record PasswordResetBody(String token, String newPassword) {}

    public record CredentialsBody(String email, String password) {}

    public record StartOperationBody(UUID operationId) {}

    public record RestartEnrollmentBody(UUID operationId, String password) {}

    public record CompleteEnrollmentBody(
            UUID operationId, String password, String authenticatorCode, boolean recoveryCodesSaved) {}

    public record Factor(String kind, String code) {}

    public record SignInBody(String email, String password, Factor secondFactor) {}

    public record ReauthenticationBody(String password, Factor secondFactor) {}

    public record PasswordChangeBody(String newPassword) {}

    public record CompleteReplacementBody(String authenticatorCode, boolean recoveryCodesSaved) {}

    public record AcknowledgementBody(boolean recoveryCodesSaved) {}
}
