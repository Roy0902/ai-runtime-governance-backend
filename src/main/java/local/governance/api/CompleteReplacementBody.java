package local.governance.api;

public record CompleteReplacementBody(String authenticatorCode, boolean recoveryCodesSaved) {}
