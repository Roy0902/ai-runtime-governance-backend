package local.governance.authentication.api.request;

public record CompleteAuthenticatorReplacementRequest(String authenticatorCode, boolean recoveryCodesSaved) {}
