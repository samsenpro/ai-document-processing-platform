package com.documind.config;

import com.documind.ai.AiServiceProperties;
import com.documind.auth.AuthenticatedUser;
import com.documind.auth.InternalApiKeyFilter;
import com.documind.auth.JwtAuthenticationFilter;
import com.documind.auth.JwtService;
import com.documind.exception.ProblemResponseWriter;
import com.documind.user.User;
import com.documind.user.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Cadena interna ({@code /internal/**}): solo la usa el servicio de IA con la clave interna. Nunca
     * acepta JWT de usuarios, así que un usuario final no puede llegar a estos endpoints.
     */
    @Bean
    @Order(1)
    SecurityFilterChain internalFilterChain(HttpSecurity http, AiServiceProperties aiProperties,
                                            ProblemResponseWriter problemWriter) throws Exception {
        return statelessDefaults(http)
                .securityMatcher("/internal/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole(InternalApiKeyFilter.ROLE))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(problemWriter).accessDeniedHandler(problemWriter))
                .addFilterBefore(new InternalApiKeyFilter(aiProperties.apiKey(), problemWriter),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /** Cadena pública: API REST autenticada con JWT, documentación y health check. */
    @Bean
    @Order(2)
    SecurityFilterChain apiFilterChain(HttpSecurity http, JwtService jwtService, UserRepository userRepository,
                                       ProblemResponseWriter problemWriter) throws Exception {
        return statelessDefaults(http)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
                                "/api/v1/auth/refresh").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Solo accesible desde la red interna de Docker (el puerto no se publica en producción)
                        .requestMatchers("/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(problemWriter).accessDeniedHandler(problemWriter))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, userRepository),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private static HttpSecurity statelessDefaults(HttpSecurity http) throws Exception {
        // API stateless con token en cabecera: no hay cookies de sesión que proteger de CSRF
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(UserRepository userRepository) {
        return email -> userRepository.findByEmail(User.normalizeEmail(email))
                .map(AuthenticatedUser::from)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder encoder) {
        // DaoAuthenticationProvider compara un hash aunque el usuario no exista, para que el tiempo de
        // respuesta no revele qué emails están registrados
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
}
