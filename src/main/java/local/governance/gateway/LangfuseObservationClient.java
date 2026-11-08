package local.governance.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.governance.api.APIException;
import local.governance.config.GovernanceProperties;
import org.springframework.stereotype.Component;

/**
 * Reads one generation observation back from Langfuse so the timeline can show
 * the model output without storing it locally.
 */
@Component
public class LangfuseObservationClient {

    static final String FIELDS = "core,basic,io,usage,model";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    public record ObservationContent(
            String observationId,
            String traceId,
            String name,
            String type,
            String model,
            String input,
            String output,
            Map<String, Object> usage,
            Map<String, Object> cost,
            String startTime,
            String endTime) {}

    private final GovernanceProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public LangfuseObservationClient(GovernanceProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getLangfuse().getTimeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    LangfuseObservationClient(
            GovernanceProperties properties,
            ObjectMapper objectMapper,
            HttpClient http) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.http = http;
    }

    public ObservationContent fetch(UUID traceId, String observationId, Instant around) {
        GovernanceProperties.LangfuseProperties langfuse = properties.getLangfuse();
        if (!langfuse.isEnabled()
                || isBlank(langfuse.getPublicKey())
                || isBlank(langfuse.getSecretKey())) {
            throw new APIException("LANGFUSE_DISABLED", "Langfuse output retrieval is not enabled.");
        }

        HttpRequest request = HttpRequest.newBuilder(uri(langfuse.getBaseUrl(), observationId, around))
                .timeout(Duration.ofSeconds(Math.max(1, langfuse.getTimeoutSeconds())))
                .header("Authorization", basic(langfuse.getPublicKey(), langfuse.getSecretKey()))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException exception) {
            throw new APIException("LANGFUSE_UNAVAILABLE", "Langfuse output retrieval timed out.");
        } catch (IOException exception) {
            throw new APIException("LANGFUSE_UNAVAILABLE", "Langfuse output retrieval is unavailable.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new APIException("LANGFUSE_UNAVAILABLE", "Langfuse output retrieval was interrupted.");
        }
        if (response.statusCode() == 404) {
            throw APIException.notFound();
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new APIException("LANGFUSE_UNAVAILABLE", "Langfuse output retrieval failed.");
        }
        return parse(response.body(), traceId, observationId);
    }

    private ObservationContent parse(String body, UUID traceId, String observationId) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw APIException.notFound();
            }
            JsonNode row = data.get(0);
            String expectedTrace = traceId.toString().replace("-", "");
            String actualTrace = text(row, "traceId");
            if (actualTrace != null && !actualTrace.equalsIgnoreCase(expectedTrace)) {
                throw APIException.notFound();
            }
            return new ObservationContent(
                    textOrDefault(row, "id", observationId),
                    expectedTrace,
                    text(row, "name"),
                    text(row, "type"),
                    text(row, "model"),
                    content(row.path("input")),
                    content(row.path("output")),
                    object(row.path("usageDetails")),
                    cost(row),
                    text(row, "startTime"),
                    text(row, "endTime"));
        } catch (APIException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new APIException("LANGFUSE_UNAVAILABLE", "Langfuse returned an unreadable response.");
        }
    }

    private Map<String, Object> cost(JsonNode row) {
        Map<String, Object> values = new LinkedHashMap<>();
        put(values, "total", row.path("totalCost"));
        put(values, "input", row.path("inputCost"));
        put(values, "output", row.path("outputCost"));
        JsonNode details = row.path("costDetails");
        if (details.isObject()) {
            details.fields().forEachRemaining(entry -> {
                if (!values.containsKey(entry.getKey())) {
                    put(values, entry.getKey(), entry.getValue());
                }
            });
        }
        return values;
    }

    private static void put(Map<String, Object> values, String key, JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isNumber()) {
            values.put(key, node.decimalValue());
        } else if (node.isTextual()) {
            values.put(key, node.asText());
        }
    }

    private Map<String, Object> object(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        try {
            return objectMapper.convertValue(node, MAP_TYPE);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private static String content(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    private static String text(JsonNode row, String field) {
        JsonNode node = row.path(field);
        return node.isTextual() ? node.asText() : null;
    }

    private static String textOrDefault(JsonNode row, String field, String fallback) {
        String value = text(row, field);
        return value == null ? fallback : value;
    }

    private URI uri(String baseUrl, String observationId, Instant around) {
        Instant center = around == null ? Instant.now() : around;
        String filter;
        try {
            filter = objectMapper.writeValueAsString(List.of(Map.of(
                    "type", "string",
                    "column", "id",
                    "operator", "=",
                    "value", observationId)));
        } catch (Exception exception) {
            throw new APIException("LANGFUSE_UNAVAILABLE", "The observation lookup could not be built.");
        }
        return URI.create(apiBase(baseUrl)
                + "/v2/observations?fields=" + FIELDS
                + "&fromStartTime=" + encode(center.minus(Duration.ofHours(1)).toString())
                + "&toStartTime=" + encode(center.plus(Duration.ofHours(1)).toString())
                + "&filter=" + encode(filter));
    }

    private static String apiBase(String baseUrl) {
        String value = baseUrl == null ? "https://cloud.langfuse.com" : baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.endsWith("/api/public") ? value : value + "/api/public";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String basic(String publicKey, String secretKey) {
        String value = publicKey + ":" + secretKey;
        return "Basic " + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
