package local.governance.auth.domain;

import local.governance.api.ApiException;

public final class PasswordPolicy {

    private PasswordPolicy() {}

    public static void validateNew(String password, String field) {
        if (password == null) {
            throw ApiException.invalid(field, "Password must be 12 to 128 characters.");
        }
        int codePoints = password.codePointCount(0, password.length());
        if (codePoints < 12 || codePoints > 128) {
            throw ApiException.invalid(field, "Password must be 12 to 128 characters.");
        }
        if (password.indexOf('\r') >= 0 || password.indexOf('\n') >= 0) {
            throw ApiException.invalid(field, "Password can't contain line breaks.");
        }
    }

    public static void requirePresent(String password, String field) {
        if (password == null) {
            throw ApiException.invalid(field, "Enter your password.");
        }
    }
}
