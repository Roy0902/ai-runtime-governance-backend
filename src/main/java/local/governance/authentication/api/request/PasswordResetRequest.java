package local.governance.authentication.api.request;

public record PasswordResetRequest(String token, String newPassword) {}
