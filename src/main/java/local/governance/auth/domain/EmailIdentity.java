package local.governance.auth.domain;

import java.util.Locale;
import java.util.regex.Pattern;
import local.governance.api.ApiException;

public final class EmailIdentity {

    private static final Pattern ADDRESS = Pattern.compile(
            "^[a-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$");

    private EmailIdentity() {}

    public static String normalize(String raw) {
        if (raw == null) {
            throw ApiException.invalid("email", "Enter a valid email address.");
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty() || trimmed.length() < 3 || trimmed.length() > 254) {
            throw ApiException.invalid("email", "Enter a valid email address.");
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (!ADDRESS.matcher(normalized).matches()) {
            throw ApiException.invalid("email", "Enter a valid email address.");
        }
        return normalized;
    }
}
