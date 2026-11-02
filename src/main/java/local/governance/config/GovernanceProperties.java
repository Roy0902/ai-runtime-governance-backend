package local.governance.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "governance")
public class GovernanceProperties {

    private String baseUrl = "http://127.0.0.1:5173";
    private Admin admin = new Admin();
    private String trustedProxies = "";
    private TestControl testControl = new TestControl();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Admin getAdmin() {
        return admin;
    }

    public void setAdmin(Admin admin) {
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

    public TestControl getTestControl() {
        return testControl;
    }

    public void setTestControl(TestControl testControl) {
        this.testControl = testControl;
    }

    public static class Admin {
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

    public static class TestControl {
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
