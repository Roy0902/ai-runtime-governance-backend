package local.governance.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import local.governance.api.APIException;
import local.governance.config.GovernanceProperties;
import org.junit.jupiter.api.Test;

class LangfuseObservationClientTest {

    @Test
    void fetchesOneObservationAndBindsItToTheTrace() throws Exception {
        UUID traceId = UUID.randomUUID();
        String traceHex = traceId.toString().replace("-", "");
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/public/v2/observations", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] body = ("""
                    {"data":[{"id":"abc123","traceId":"%s","name":"chat gpt-4o","type":"GENERATION",
                    "model":"gpt-4o","input":"hi","output":"the answer",
                    "usageDetails":{"input":3,"output":2},
                    "totalCost":"0.02","inputCost":"0.01","outputCost":"0.01",
                    "startTime":"2026-11-08T00:00:00Z","endTime":"2026-11-08T00:00:01Z"}]}
                    """).formatted(traceHex).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            LangfuseObservationClient client = new LangfuseObservationClient(
                    properties(server.getAddress().getPort()), new ObjectMapper());

            LangfuseObservationClient.ObservationContent content =
                    client.fetch(traceId, "abc123", Instant.parse("2026-11-08T00:00:00Z"));

            assertEquals("abc123", content.observationId());
            assertEquals(traceHex, content.traceId());
            assertEquals("the answer", content.output());
            assertEquals("gpt-4o", content.model());
            assertEquals(3, ((Number) content.usage().get("input")).intValue());
            assertTrue(query.get().contains("filter="));
            assertFalse(query.get().contains("traceId"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesWhenLangfuseIsDisabled() {
        LangfuseObservationClient client =
                new LangfuseObservationClient(new GovernanceProperties(), new ObjectMapper());

        APIException exception = assertThrows(
                APIException.class,
                () -> client.fetch(UUID.randomUUID(), "abc", Instant.now()));

        assertEquals("LANGFUSE_DISABLED", exception.code());
    }

    private static GovernanceProperties properties(int port) {
        GovernanceProperties properties = new GovernanceProperties();
        properties.getLangfuse().setEnabled(true);
        properties.getLangfuse().setBaseUrl("http://127.0.0.1:" + port);
        properties.getLangfuse().setPublicKey("pk");
        properties.getLangfuse().setSecretKey("sk");
        return properties;
    }
}
