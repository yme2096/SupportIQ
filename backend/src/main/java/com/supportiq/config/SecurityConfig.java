package com.supportiq.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    private final String jwtSecret;
    private final String corsOrigin;

    public SecurityConfig(@Value("${app.security.jwt-secret}") String jwtSecret,
                          @Value("${app.cors.origin}") String corsOrigin) {
        if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("SUPPORTIQ_JWT_SECRET must contain at least 32 bytes.");
        }
        this.jwtSecret = jwtSecret;
        this.corsOrigin = corsOrigin;
    }

    private SecretKey signingKey() {
        return new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean FilterRegistrationBean<SubscriptionAccessFilter> subscriptionFilterRegistration(SubscriptionAccessFilter filter) { FilterRegistrationBean<SubscriptionAccessFilter> registration=new FilterRegistrationBean<>(filter);registration.setEnabled(false);return registration; }

    @Bean JwtEncoder jwtEncoder() { return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey())); }

    @Bean JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey()).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> issuer = JwtValidators.createDefaultWithIssuer("supportiq");
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience().contains("supportiq-api")
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, audience));
        return decoder;
    }

    @Bean JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> List.of(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role"))));
        return converter;
    }

    @Bean CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(corsOrigin));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Conversation-Token", "X-Order-Access-Token", "Idempotency-Key", "X-Provider-Signature", "X-Requested-With"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration widgetCors = new CorsConfiguration();
        widgetCors.setAllowedOriginPatterns(List.of("*"));
        widgetCors.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        widgetCors.setAllowedHeaders(List.of("Content-Type", "X-Conversation-Token", "X-Order-Access-Token", "Idempotency-Key"));
        widgetCors.setAllowCredentials(false);
        widgetCors.setMaxAge(3600L);
        source.registerCorsConfiguration("/api/public/widget/**", widgetCors);
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter converter, SubscriptionAccessFilter subscriptionAccessFilter) throws Exception {
        http.cors(Customizer.withDefaults())
            // The only authentication cookie is SameSite=Strict and state-changing auth requests are Origin-checked.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(headers -> headers.frameOptions(frame -> frame.deny()).contentTypeOptions(Customizer.withDefaults()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/ws", "/ws/**", "/api/v1/health", "/api/auth/register-company", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout", "/api/auth/forgot-password", "/api/auth/reset-password", "/api/public/**", "/api/payments/webhooks/**", "/api/v1/chat/message", "/api/v1/chat/*/messages", "/api/invitations/validate", "/api/invitations/accept", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/api/public/orders/**").permitAll()
                .requestMatchers("/api/platform/**").hasRole("SUPER_ADMIN")
                .requestMatchers("/api/v1/billing/**").hasRole("COMPANY_ADMIN")
                .requestMatchers("/api/v1/orders/**").hasAnyRole("AGENT", "COMPANY_ADMIN", "SUPER_ADMIN")
                .requestMatchers("/api/v1/agent/**").hasAnyRole("AGENT", "COMPANY_ADMIN", "SUPER_ADMIN")
                .requestMatchers("/api/v1/analytics", "/api/v1/knowledge/**").hasAnyRole("COMPANY_ADMIN", "SUPER_ADMIN")
                .requestMatchers("/api/v1/chat/conversations").hasAnyRole("AGENT", "COMPANY_ADMIN", "SUPER_ADMIN")
                .requestMatchers("/api/company/**", "/api/users/**", "/api/invitations/**").hasAnyRole("COMPANY_ADMIN", "SUPER_ADMIN")
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
            .addFilterAfter(subscriptionAccessFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
