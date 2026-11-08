package local.governance.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ModelUsageRecorderTest {

    @Test
    void storesTheLangfuseObservationId() {
        ModelUsageRepository repository = mock(ModelUsageRepository.class);
        ModelUsageRecorder recorder = new ModelUsageRecorder(
                repository,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-11-08T00:00:00Z"), ZoneOffset.UTC));
        UUID traceId = Uuid7.generate();

        recorder.record(new ModelUsageRecorder.Usage(
                UUID.randomUUID(),
                traceId,
                null,
                "conversation-key",
                null,
                null,
                "openai-compatible",
                "gpt-4o",
                "gpt-4o-2024-08-06",
                "resp_1",
                "fp_1",
                "default",
                false,
                200,
                "completed",
                List.of("stop"),
                List.of(),
                10L,
                5L,
                2L,
                1L,
                15L,
                null,
                null,
                null,
                null,
                null,
                "USD",
                100,
                200L,
                false,
                120,
                80,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                "0123456789abcdef"));

        ArgumentCaptor<ModelUsageRow> row = ArgumentCaptor.forClass(ModelUsageRow.class);
        verify(repository).insert(row.capture());
        assertEquals("0123456789abcdef", row.getValue().langfuseObservationId());
        assertEquals(traceId, row.getValue().traceId());
    }
}
