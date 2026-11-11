package local.governance.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import local.governance.authentication.session.SessionService;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;

/**
 * Keeps the existing native-auth application running unchanged while the
 * Keycloak BFF is introduced behind the "keycloak" profile.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @ConditionalOnProperty(name = "governance.iam.mode", havingValue = "native", matchIfMissing = true)
    SecurityFilterChain nativeSecurityFilterChain(
            HttpSecurity http,
            SessionService sessions,
            AppUserService users) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement((session) -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests((authorize) -> authorize.anyRequest().permitAll())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterAfter(
                        new NativeSessionAuthenticationFilter(sessions, users),
                        SecurityContextHolderFilter.class);
        return http.build();
    }

    @Bean
    @Order(1)
    @ConditionalOnProperty(name = "governance.iam.mode", havingValue = "keycloak")
    SecurityFilterChain keycloakBffFilterChain(
            HttpSecurity http,
            ObjectProvider<OAuth2AuthorizationRequestResolver> resolverProvider,
            ObjectProvider<LogoutSuccessHandler> logoutSuccessHandlerProvider,
            SessionService sessions,
            AppUserService users) throws Exception {
        http
                .securityMatcher("/api/**")
                .authorizeHttpRequests((authorize) -> authorize
                        .requestMatchers(
                                "/api/v1/auth/**",
                                "/api/v1/keycloak/**",
                                "/api/v1/session/**",
                                "/api/v1/hooks/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2Login((oauth) -> oauth
                        .authorizationEndpoint((endpoint) -> {
                            endpoint.baseUri("/api/v1/keycloak/authorize");
                            OAuth2AuthorizationRequestResolver resolver = resolverProvider.getIfAvailable();
                            if (resolver != null) {
                                endpoint.authorizationRequestResolver(resolver);
                            }
                        })
                        .redirectionEndpoint((endpoint) -> endpoint.baseUri("/api/v1/keycloak/callback"))
                        .defaultSuccessUrl("/", true))
                .logout((logout) -> {
                    logout.logoutUrl("/api/v1/keycloak/logout")
                            .invalidateHttpSession(true)
                            .clearAuthentication(true);
                    LogoutSuccessHandler handler = logoutSuccessHandlerProvider.getIfAvailable();
                    if (handler != null) {
                        logout.logoutSuccessHandler(handler);
                    } else {
                        logout.logoutSuccessUrl("/signed-out");
                    }
                })
                .csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling((exceptions) -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterAfter(
                        new NativeSessionAuthenticationFilter(sessions, users),
                        SecurityContextHolderFilter.class)
                .addFilterAfter(
                        new UnifiedCsrfFilter(),
                        NativeSessionAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    @ConditionalOnProperty(name = "governance.iam.mode", havingValue = "keycloak")
    SecurityFilterChain keycloakFallbackFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement((session) -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests((authorize) -> authorize.anyRequest().permitAll())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
