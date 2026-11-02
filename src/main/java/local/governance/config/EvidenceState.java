package local.governance.config;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class EvidenceState {

    private final List<String> fields = List.of(
            "scenarioId", "correlationId", "state", "version", "counts", "timings");

    public List<String> fields() {
        return fields;
    }

    public void export() {
        // Metadata-only export marker for the acceptance harness.
    }
}
