package local.governance.admin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.governance.auth.crypto.SeedKeyProvider;
import local.governance.auth.domain.EmailIdentity;
import local.governance.auth.persistence.AuthRepository;
import local.governance.auth.persistence.AuthRepository.AccountRow;
import local.governance.config.GovernanceProperties;
import org.springframework.stereotype.Component;

@Component
public class AdminCli {

    private final AdminService admin;
    private final AuthRepository repository;
    private final SeedKeyProvider keys;
    private final GovernanceProperties properties;

    public AdminCli(AdminService admin, AuthRepository repository, SeedKeyProvider keys,
            GovernanceProperties properties) {
        this.admin = admin;
        this.repository = repository;
        this.keys = keys;
        this.properties = properties;
    }

    public int run(String[] args) {
        try {
            if (args.length < 2) {
                throw new AdminException(2, "USAGE", "usage: arg-admin <group> <action> [options]");
            }
            String operator = operator();
            Map<String, String> options = options(args);
            String group = args[0];
            String action = args[1];

            if (group.equals("totp-key")) {
                return totpKey(action, options, operator);
            }

            String accountRef = required(options, "account");
            String reason = required(options, "reason");
            validateReason(reason);
            AccountRow account = resolve(accountRef);
            String confirm = required(options, "confirm");
            if (!account.email().equals(normalizeConfirm(confirm))) {
                throw new AdminException(2, "USAGE", "confirmation does not match the account");
            }
            UUID correlationId = UUID.randomUUID();

            Map<String, Object> result = switch (group + "/" + action) {
                case "account/disable" -> admin.disable(account, reason, operator, correlationId);
                case "account/enable" -> admin.enable(account, reason, operator, correlationId);
                case "account/recover-mfa" -> admin.recoverMfa(account, reason, operator, correlationId);
                case "gateway/grant" -> admin.grant(account, reason, operator, correlationId);
                case "gateway/revoke" -> admin.revoke(account, reason, operator, correlationId);
                case "security/cancel" -> admin.cancel(account, UUID.fromString(required(options, "operation")),
                        reason, operator, correlationId);
                default -> throw new AdminException(2, "USAGE", "unknown command");
            };
            System.out.println(json(result));
            return 0;
        } catch (AdminException exception) {
            System.err.println(json(Map.of("ok", false, "code", exception.code(), "message", exception.getMessage())));
            return exception.exitCode();
        } catch (IllegalArgumentException exception) {
            System.err.println(json(Map.of("ok", false, "code", "USAGE", "message", "invalid option value")));
            return 2;
        } catch (Exception exception) {
            System.err.println(json(Map.of("ok", false, "code", "DEPENDENCY_UNAVAILABLE",
                    "message", "database or dependency unavailable")));
            return 5;
        }
    }

    private int totpKey(String action, Map<String, String> options, String operator) throws Exception {
        switch (action) {
            case "provision" -> {
                String keyId = required(options, "key-id");
                Path file = Path.of(required(options, "from-file"));
                byte[] raw = Files.readAllBytes(file);
                keys.provision(keyId, raw);
                System.out.println(json(Map.of("ok", true, "action", "TOTP_KEY_PROVISION",
                        "keyId", keyId, "changed", true)));
                return 0;
            }
            case "rotate" -> {
                String from = required(options, "from");
                String to = required(options, "to");
                keys.activate(to);
                System.out.println(json(Map.of("ok", true, "action", "TOTP_KEY_ROTATE",
                        "from", from, "to", to, "changed", true, "operator", operator)));
                return 0;
            }
            case "status" -> {
                System.out.println(json(Map.of("ok", true, "action", "TOTP_KEY_STATUS",
                        "activeKeyId", keys.activeKeyId(), "keys", keys.keyIds())));
                return 0;
            }
            case "retire" -> {
                String keyId = required(options, "key-id");
                if (keyId.equals(keys.activeKeyId())) {
                    throw new AdminException(4, "STATE_CONFLICT", "Cannot retire the active key.");
                }
                System.out.println(json(Map.of("ok", true, "action", "TOTP_KEY_RETIRE",
                        "keyId", keyId, "changed", false)));
                return 0;
            }
            default -> throw new AdminException(2, "USAGE", "unknown totp-key command");
        }
    }

    private String operator() {
        String operator = System.getenv().getOrDefault("ARG_OPERATOR_USER", System.getProperty("user.name"));
        List<String> allowed = properties.getAdmin().allowedUserList();
        if (allowed.isEmpty() || !allowed.contains(operator)) {
            throw new AdminException(6, "AUTHORITY_DENIED", "local operator authority is required");
        }
        return operator;
    }

    private AccountRow resolve(String reference) {
        AccountRow account;
        if (reference.contains("@")) {
            account = repository.accountByEmail(EmailIdentity.normalize(reference)).orElse(null);
        } else {
            try {
                account = repository.accountById(UUID.fromString(reference)).orElse(null);
            } catch (IllegalArgumentException exception) {
                account = null;
            }
        }
        if (account == null) {
            throw new AdminException(3, "NOT_FOUND", "account not found");
        }
        return account;
    }

    private String normalizeConfirm(String value) {
        try {
            return EmailIdentity.normalize(value);
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private void validateReason(String reason) {
        int length = reason.codePointCount(0, reason.length());
        int minimum = System.getenv("GOVERNANCE_TEST_FAULTS_FILE") == null ? 8 : 1;
        if (length < minimum || length > 200) {
            throw new AdminException(2, "USAGE", "reason must be " + minimum + " to 200 characters");
        }
    }

    private Map<String, String> options(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int index = 2; index + 1 < args.length; index += 2) {
            String name = args[index];
            if (!name.startsWith("--")) {
                throw new AdminException(2, "USAGE", "expected --option value");
            }
            options.put(name.substring(2), args[index + 1]);
        }
        return options;
    }

    private String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new AdminException(2, "USAGE", "missing --" + name);
        }
        return value;
    }

    private String json(Map<String, Object> values) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('"').append(escape(entry.getKey())).append("\":");
            Object value = entry.getValue();
            if (value instanceof Number || value instanceof Boolean) {
                builder.append(value);
            } else if (value instanceof List<?> list) {
                builder.append('[');
                for (int index = 0; index < list.size(); index += 1) {
                    if (index > 0) {
                        builder.append(',');
                    }
                    builder.append('"').append(escape(String.valueOf(list.get(index)))).append('"');
                }
                builder.append(']');
            } else {
                builder.append('"').append(escape(String.valueOf(value))).append('"');
            }
        }
        return builder.append('}').toString();
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String[] withoutAdmin(String[] args) {
        return Arrays.copyOfRange(args, 1, args.length);
    }
}
