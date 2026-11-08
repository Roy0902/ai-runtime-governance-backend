package local.governance.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Adds prompt=login to the Keycloak authorization request when the web app asks
 * for step-up authentication. A normal login keeps Keycloak SSO behaviour.
 */
public class KeycloakAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    public KeycloakAuthorizationRequestResolver(ClientRegistrationRepository repository) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(repository, "/api/v1/keycloak/authorize");
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return customize(delegate.resolve(request), request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return customize(delegate.resolve(request, clientRegistrationId), request);
    }

    private OAuth2AuthorizationRequest customize(OAuth2AuthorizationRequest request, HttpServletRequest servletRequest) {
        if (request == null) {
            return null;
        }
        Map<String, Object> parameters = new LinkedHashMap<>(request.getAdditionalParameters());
        if ("1".equals(servletRequest.getParameter("stepUp"))) {
            parameters.put("prompt", "login");
        }
        String idpHint = servletRequest.getParameter("kc_idp_hint");
        if (idpHint != null && !idpHint.isBlank()) {
            parameters.put("kc_idp_hint", idpHint);
        }
        if (parameters.isEmpty()) {
            return request;
        }
        return OAuth2AuthorizationRequest.from(request)
                .additionalParameters(parameters)
                .build();
    }
}
