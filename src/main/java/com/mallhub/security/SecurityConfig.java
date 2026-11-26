package com.mallhub.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                // Order matters: the most specific rules come first.
                .requestMatchers("/login", "/register", "/error", "/actuator/health", "/ws/**").permitAll()
                .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()
                // Only a super admin approves or rejects offers
                .requestMatchers(HttpMethod.POST, "/admin/offers/*/approve", "/admin/offers/*/reject").hasRole("SUPER_ADMIN")
                .requestMatchers("/admin/**").hasAnyRole("MALL_ADMIN", "SUPER_ADMIN")
                .requestMatchers(HttpMethod.GET, "/malls/new").hasAnyRole("MALL_ADMIN", "SUPER_ADMIN")
                .requestMatchers(HttpMethod.POST, "/malls", "/api/malls").hasAnyRole("MALL_ADMIN", "SUPER_ADMIN")
                // Public browsing
                .requestMatchers(HttpMethod.GET, "/", "/malls/**", "/stores", "/offers", "/parking", "/api/**").permitAll()
                // Everything else (book parking, loyalty, notifications) needs a login
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/", true)
                .permitAll()
            )
            .logout(logout -> logout.logoutSuccessUrl("/").permitAll())
            // Lets curl/Postman call the JSON API with email:password
            .httpBasic(Customizer.withDefaults())
            // Website forms carry a CSRF token automatically (Thymeleaf adds it); the JSON API is stateless
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"));
        return http.build();
    }
}
