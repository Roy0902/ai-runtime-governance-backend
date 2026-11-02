package local.governance.api;

public record SignInBody(String email, String password, Factor secondFactor) {}
