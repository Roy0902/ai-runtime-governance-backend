package local.governance.integration;

import local.governance.api.APIException;

public enum HookIntegration {
    LANGCHAIN4J("langchain4j", "/api/v1/hooks/langchain4j/events"),
    LANGFUSE("langfuse", "/api/v1/hooks/langfuse/events");

    private final String id;
    private final String eventsPath;

    HookIntegration(String id, String eventsPath) {
        this.id = id;
        this.eventsPath = eventsPath;
    }

    public String id() {
        return id;
    }

    public String eventsPath() {
        return eventsPath;
    }

    public static HookIntegration fromId(String id) {
        for (HookIntegration integration : values()) {
            if (integration.id.equals(id)) {
                return integration;
            }
        }
        throw APIException.invalid("integration", "Choose LangChain4j or Langfuse.");
    }
}
