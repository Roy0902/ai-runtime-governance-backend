package local.governance.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

@Configuration
@Profile("keycloak")
public class KeycloakAuthorizationConfig {

    @Bean
    OAuth2AuthorizationRequestResolver keycloakAuthorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository) {
        return new KeycloakAuthorizationRequestResolver(clientRegistrationRepository);
    }

    @Bean
    LogoutSuccessHandler keycloakLogoutSuccessHandler(ClientRegistrationRepository clientRegistrationRepository) {
        OidcClientInitiatedLogoutSuccessHandler handler =
                new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
        handler.setPostLogoutRedirectUri("{baseUrl}/signed-out");
        return handler;
    }
}
