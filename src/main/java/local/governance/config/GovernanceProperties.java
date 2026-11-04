package local.governance.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "governance")
public class GovernanceProperties {

    private String baseUrl = "http://127.0.0.1:5173";
    private AdminProperties admin = new AdminProperties();
    private String trustedProxies = "";
    private TestControlProperties testControl = new TestControlProperties();
    private TimelineProperties timeline = new TimelineProperties();
    private GatewayProperties gateway = new GatewayProperties();
    private LangfuseProperties langfuse = new LangfuseProperties();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public AdminProperties getAdmin() {
        return admin;
    }

    public void setAdmin(AdminProperties admin) {
        this.admin = admin;
    }

    public String getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(String trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    public List<String> trustedProxyList() {
        if (trustedProxies == null || trustedProxies.isBlank()) {
            return List.of();
        }
        return Arrays.stream(trustedProxies.split(",")).map(String::trim).filter((s) -> !s.isEmpty()).toList();
    }

    public TestControlProperties getTestControl() {
        return testControl;
    }

    public void setTestControl(TestControlProperties testControl) {
        this.testControl = testControl;
    }

    public TimelineProperties getTimeline() {
        return timeline;
    }

    public void setTimeline(TimelineProperties timeline) {
        this.timeline = timeline;
    }

    public GatewayProperties getGateway() {
        return gateway;
    }

    public void setGateway(GatewayProperties gateway) {
        this.gateway = gateway;
    }

    public LangfuseProperties getLangfuse() {
        return langfuse;
    }

    public void setLangfuse(LangfuseProperties langfuse) {
        this.langfuse = langfuse;
    }

    public static class GatewayProperties {
        private boolean enabled = true;
        private String publicBaseUrl = "";
        private String upstreamBaseUrl = "";
        private String upstreamApiKey = "";
        private String upstreamOrganization = "";
        private String upstreamProject = "";
        private String models = "";
        private int maxActiveKeys = 5;
        private int maxRequestBytes = 2_097_152;
        private int maxCaptureBytes = 2_097_152;
        private int maxConcurrentUpstream = 8;
        private int connectTimeoutSeconds = 10;
        private int requestTimeoutSeconds = 120;
        private int conversationTtlSeconds = 3_600;
        private int requestsPerMinute = 60;
        private boolean allowUnguardedContinuation = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPublicBaseUrl() {
            return publicBaseUrl;
        }

        public void setPublicBaseUrl(String publicBaseUrl) {
            this.publicBaseUrl = publicBaseUrl;
        }

        public String getUpstreamBaseUrl() {
            return upstreamBaseUrl;
        }

        public void setUpstreamBaseUrl(String upstreamBaseUrl) {
            this.upstreamBaseUrl = upstreamBaseUrl;
        }

        public String getUpstreamApiKey() {
            return upstreamApiKey;
        }

        public void setUpstreamApiKey(String upstreamApiKey) {
            this.upstreamApiKey = upstreamApiKey;
        }

        public String getUpstreamOrganization() {
            return upstreamOrganization;
        }

        public void setUpstreamOrganization(String upstreamOrganization) {
            this.upstreamOrganization = upstreamOrganization;
        }

        public String getUpstreamProject() {
            return upstreamProject;
        }

        public void setUpstreamProject(String upstreamProject) {
            this.upstreamProject = upstreamProject;
        }

        public String getModels() {
            return models;
        }

        public void setModels(String models) {
            this.models = models;
        }

        public List<String> modelList() {
            if (models == null || models.isBlank()) {
                return List.of();
            }
            return Arrays.stream(models.split(",")).map(String::trim).filter((s) -> !s.isEmpty()).toList();
        }

        public int getMaxActiveKeys() {
            return maxActiveKeys;
        }

        public void setMaxActiveKeys(int maxActiveKeys) {
            this.maxActiveKeys = maxActiveKeys;
        }

        public int getMaxRequestBytes() {
            return maxRequestBytes;
        }

        public void setMaxRequestBytes(int maxRequestBytes) {
            this.maxRequestBytes = maxRequestBytes;
        }

        public int getMaxCaptureBytes() {
            return maxCaptureBytes;
        }

        public void setMaxCaptureBytes(int maxCaptureBytes) {
            this.maxCaptureBytes = maxCaptureBytes;
        }

        public int getMaxConcurrentUpstream() {
            return maxConcurrentUpstream;
        }

        public void setMaxConcurrentUpstream(int maxConcurrentUpstream) {
            this.maxConcurrentUpstream = maxConcurrentUpstream;
        }

        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public int getRequestTimeoutSeconds() {
            return requestTimeoutSeconds;
        }

        public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
            this.requestTimeoutSeconds = requestTimeoutSeconds;
        }

        public int getConversationTtlSeconds() {
            return conversationTtlSeconds;
        }

        public void setConversationTtlSeconds(int conversationTtlSeconds) {
            this.conversationTtlSeconds = conversationTtlSeconds;
        }

        public int getRequestsPerMinute() {
            return requestsPerMinute;
        }

        public void setRequestsPerMinute(int requestsPerMinute) {
            this.requestsPerMinute = requestsPerMinute;
        }

        public boolean isAllowUnguardedContinuation() {
            return allowUnguardedContinuation;
        }

        public void setAllowUnguardedContinuation(boolean allowUnguardedContinuation) {
            this.allowUnguardedContinuation = allowUnguardedContinuation;
        }
    }

    public static class LangfuseProperties {
        private boolean enabled = false;
        private String baseUrl = "https://cloud.langfuse.com";
        private String publicKey = "";
        private String secretKey = "";
        private boolean captureInput = true;
        private boolean captureOutput = true;
        private int queueCapacity = 1_000;
        private int timeoutSeconds = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public boolean isCaptureInput() {
            return captureInput;
        }

        public void setCaptureInput(boolean captureInput) {
            this.captureInput = captureInput;
        }

        public boolean isCaptureOutput() {
            return captureOutput;
        }

        public void setCaptureOutput(boolean captureOutput) {
            this.captureOutput = captureOutput;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    public static class TimelineProperties {
        private int maxMembers = 10_000;
        private int maxActive = 5;
        private int ttlSeconds = 300;

        public int getMaxMembers() {
            return maxMembers;
        }

        public void setMaxMembers(int maxMembers) {
            this.maxMembers = maxMembers;
        }

        public int getMaxActive() {
            return maxActive;
        }

        public void setMaxActive(int maxActive) {
            this.maxActive = maxActive;
        }

        public int getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(int ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }
    }

    public static class AdminProperties {
        private String allowedOsUsers = "";

        public String getAllowedOsUsers() {
            return allowedOsUsers;
        }

        public void setAllowedOsUsers(String allowedOsUsers) {
            this.allowedOsUsers = allowedOsUsers;
        }

        public List<String> allowedUserList() {
            if (allowedOsUsers == null || allowedOsUsers.isBlank()) {
                return List.of();
            }
            return Arrays.stream(allowedOsUsers.split(",")).map(String::trim).filter((s) -> !s.isEmpty()).toList();
        }
    }

    public static class TestControlProperties {
        private boolean enabled;
        private String token = "";
        private String clockFile = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getClockFile() {
            return clockFile;
        }

        public void setClockFile(String clockFile) {
            this.clockFile = clockFile;
        }
    }
}
