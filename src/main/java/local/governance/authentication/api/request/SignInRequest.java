package local.governance.authentication.api.request;

public record SignInRequest(String email, String password, AuthenticationFactor secondFactor) {}
