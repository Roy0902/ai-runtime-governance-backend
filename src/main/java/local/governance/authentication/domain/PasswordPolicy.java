package local.governance.authentication.domain;

import local.governance.api.APIException;

public final class PasswordPolicy {

    private PasswordPolicy() {}

    public static void validateNew(String password, String field) {
        if (password == null) {
            throw APIException.invalid(field, "Password must be 12 to 128 characters.");
        }
        int codePoints = password.codePointCount(0, password.length());
        if (codePoints < 12 || codePoints > 128) {
            throw APIException.invalid(field, "Password must be 12 to 128 characters.");
        }
        if (password.indexOf('\r') >= 0 || password.indexOf('\n') >= 0) {
            throw APIException.invalid(field, "Password can't contain line breaks.");
        }
    }

    public static void requirePresent(String password, String field) {
        if (password == null) {
            throw APIException.invalid(field, "Enter your password.");
        }
    }
}
