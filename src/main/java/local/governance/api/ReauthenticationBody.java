package local.governance.api;

public record ReauthenticationBody(String password, Factor secondFactor) {}
