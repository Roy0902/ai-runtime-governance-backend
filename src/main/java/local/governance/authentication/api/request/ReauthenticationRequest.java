package local.governance.authentication.api.request;

public record ReauthenticationRequest(String password, AuthenticationFactor secondFactor) {}
