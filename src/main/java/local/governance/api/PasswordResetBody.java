package local.governance.api;

public record PasswordResetBody(String token, String newPassword) {}
