package com.deezywallet.payment.config;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.deezywallet.payment.constants.PaymentConstants;
import com.deezywallet.payment.security.JwtAuthFilter;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Security configuration for Payment Service.
 *
 * Route authorization matrix:
 * ─────────────────────────────────────────────────────────────────────
 *  /actuator/health                   → Public
 *  /api/v1/payments/webhooks/**       → Public (gateway signature verified in handler)
 *  /api/v1/admin/payments/**          → ROLE_ADMIN
 *  /internal/v1/payments/**           → ROLE_INTERNAL_SERVICE
 *  /api/v1/payments/**                → Authenticated (USER or MERCHANT)
 * ─────────────────────────────────────────────────────────────────────
 *
 * WEBHOOK SECURITY NOTE:
 *   Webhooks are permitAll() because Stripe/Razorpay servers cannot present
 *   a user JWT. Security is enforced at the application layer via HMAC-SHA256
 *   signature verification in WebhookController before any processing occurs.
 *   Reject on invalid signature → never process unverified webhook payloads.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

	private final JwtAuthFilter jwtAuthFilter;
	private final ObjectMapper  objectMapper;

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		return http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.exceptionHandling(e -> e
						.authenticationEntryPoint((req, res, ex) -> {
							res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
							res.setContentType(MediaType.APPLICATION_JSON_VALUE);
							objectMapper.writeValue(res.getOutputStream(), Map.of(
									"errorCode", "AUTH_FAILED",
									"message",   "Authentication required",
									"timestamp", LocalDateTime.now().toString()
							));
						})
						.accessDeniedHandler((req, res, ex) -> {
							res.setStatus(HttpServletResponse.SC_FORBIDDEN);
							res.setContentType(MediaType.APPLICATION_JSON_VALUE);
							objectMapper.writeValue(res.getOutputStream(), Map.of(
									"errorCode", "ACCESS_DENIED",
									"message",   "Insufficient permissions",
									"timestamp", LocalDateTime.now().toString()
							));
						})
				)
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(PaymentConstants.ACTUATOR_HEALTH).permitAll()
						// Webhooks: public — security enforced by signature verification
						.requestMatchers(PaymentConstants.API_PAYMENT_BASE + "/webhooks/**").permitAll()
						.requestMatchers(PaymentConstants.API_ADMIN_BASE + "/**").hasRole("ADMIN")
						.requestMatchers(PaymentConstants.API_INTERNAL_BASE + "/**").hasRole("INTERNAL_SERVICE")
						.anyRequest().authenticated()
				)
				.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
				.build();
	}
}
