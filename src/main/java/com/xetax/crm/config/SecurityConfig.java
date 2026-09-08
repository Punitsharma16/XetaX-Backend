package com.xetax.crm.config;

import com.xetax.crm.auth.security.JwtAuthenticationFilter;
import com.xetax.crm.auth.security.Oauth2SuccessHandler;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Security for the whole application.
 *
 * <p>Auth is no longer a separate service and there is no API gateway in
 * front: this application issues tokens (AuthTokenController), manages users
 * (AuthUserController) and verifies every request's access token itself
 * (JwtAuthenticationFilter). The permitAll list below reproduces exactly what
 * the gateway's RouteValidator used to leave open, so the effective public
 * surface is unchanged.
 *
 * <p>CORS also moves here from the gateway — the Angular dev server on
 * http://localhost:5000 talks to this port directly now.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @org.springframework.beans.factory.annotation.Value("${app.public-base-url:http://localhost:5000}")
    private String publicBaseUrl;

    /** Panel origins allowed to call the API — env-driven for production. */
    @org.springframework.beans.factory.annotation.Value("${app.cors-origins}")
    private String corsOrigins;

    @Autowired
    JwtAuthenticationFilter jwtAuthenticationFilter;
    @Autowired
    Oauth2SuccessHandler oauth2SuccessHandler;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/auth/v1/login",
                                "/auth/v1/refresh",
                                "/auth/v1/logoutRefreshToken",
                                "/auth/v1/forgot-password",
                                "/auth/v1/reset-password",
                                "/auth/api/v1/users/register").permitAll()
                        .requestMatchers(
                                "/oauth2/**",
                                "/login/oauth2/**",
                                "/api/public/integrations/**",
                                "/api/public/whatsapp/**",
                                "/api/public/meetings/**",
                                "/api/public/agents/**",
                                "/api/public/forms/**",
                                "/ws/**",
                                "/actuator/health", "/actuator/health/**",
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .oauth2Login(oauth2 -> oauth2
                        .successHandler(oauth2SuccessHandler)
                        .failureHandler((request, response, exception) ->
                                response.sendRedirect(publicBaseUrl + "/login?oauth=failed")))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json");
                            response.getWriter().write(
                                    "{\"error\":\"Unauthorized\",\"message\":\"Invalid or Missing Token\"}"
                            );
                        }))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /** Same origins/headers the gateway's CorsConfig allowed. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of(corsOrigins.split(",")));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        // Embeddable agent widget lives on OTHER people's websites — its public
        // endpoints must accept any origin (no credentials, GET/POST only).
        CorsConfiguration publicAgent = new CorsConfiguration();
        publicAgent.setAllowedOriginPatterns(List.of("*"));
        publicAgent.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        publicAgent.setAllowedHeaders(List.of("*"));
        publicAgent.setAllowCredentials(false);
        publicAgent.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/public/agents/**", publicAgent);
        // Public lead-capture form / webhook — embedded on other people's sites too.
        source.registerCorsConfiguration("/api/public/forms/**", publicAgent);
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
