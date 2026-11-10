package local.governance.provider;

import java.util.Arrays;
import java.util.Optional;

public enum ProviderKind {

    LANGSMITH("LangSmith"),
    LANGFUSE("Langfuse");

    private final String label;

    ProviderKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Optional<ProviderKind> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(kind -> kind.name().equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
