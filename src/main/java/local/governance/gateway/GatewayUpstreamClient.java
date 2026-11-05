package local.governance.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import local.governance.api.APIException;
import local.governance.config.GovernanceProperties;
import org.springframework.stereotype.Service;

@Service
public class GatewayUpstreamClient {

    private final GovernanceProperties properties;
    private final HttpClient http;

    public GatewayUpstreamClient(GovernanceProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getGateway().getConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public HttpResponse<InputStream> sendChatCompletion(byte[] body, boolean stream) {
        return sendChatCompletion(body, stream, null);
    }

    public HttpResponse<InputStream> sendChatCompletion(byte[] body, boolean stream, String sessionId) {
        GovernanceProperties.GatewayProperties gateway = properties.getGateway();
        if (isBlank(gateway.getUpstreamBaseUrl()) || isBlank(gateway.getUpstreamApiKey())) {
            throw new APIException(
                    "UPSTREAM_UNAVAILABLE",
                    "The model gateway has no upstream provider configured.");
        }

        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(uri(gateway.getUpstreamBaseUrl()))
                    .timeout(Duration.ofSeconds(gateway.getRequestTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Accept", stream ? "text/event-stream" : "application/json")
                    .header("Accept-Encoding", "identity")
                    .header("Authorization", "Bearer " + gateway.getUpstreamApiKey())
                    .header("User-Agent", "ai-runtime-governance/0.1")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        } catch (IllegalArgumentException exception) {
            throw new APIException("UPSTREAM_UNAVAILABLE", "The upstream URL is not valid.");
        }
        if (sessionId != null && !sessionId.isBlank()) {
            request.header("x-opencode-session", sessionId);
        }
        if (!isBlank(gateway.getUpstreamOrganization())) {
            request.header("OpenAI-Organization", gateway.getUpstreamOrganization());
        }
        if (!isBlank(gateway.getUpstreamProject())) {
            request.header("OpenAI-Project", gateway.getUpstreamProject());
        }

        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException exception) {
            throw new APIException("UPSTREAM_TIMEOUT", "The upstream model provider timed out.");
        } catch (IOException exception) {
            throw new APIException("UPSTREAM_UNAVAILABLE", "The upstream model provider is unavailable.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new APIException("UPSTREAM_UNAVAILABLE", "The upstream request was interrupted.");
        }
    }

    private static URI uri(String baseUrl) {
        String value = baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return URI.create(value + "/chat/completions");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
