package com.deezywallet.payment.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.deezywallet.payment.constants.PaymentConstants;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * JWT auth filter — same pattern as User, Wallet, Transaction services.
 *
 * WEBHOOK PATHS BYPASS JWT:
 *   /api/v1/payments/webhooks/** is excluded from JWT auth.
 *   Webhooks come from Stripe/Razorpay servers — they don't have user JWTs.
 *   Security for webhooks is signature-based (HMAC-SHA256) verified in the
 *   webhook controller, not JWT-based.
 *
 *   WHY not configure permitAll() for webhooks in SecurityConfig?
 *   We DO configure permitAll() in SecurityConfig. This filter still runs but
 *   the absence of a Bearer token results in anonymous context — Spring Security
 *   then checks if the route is permitted for anonymous users (it is, via permitAll).
 *   This filter just cleanly skips webhook paths to avoid noisy "no token" debug logs.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

	private final JwtTokenValidator tokenValidator;

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		// Webhook paths authenticate via gateway signature, not JWT
		return request.getRequestURI().startsWith(PaymentConstants.API_PAYMENT_BASE + "/webhooks");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest  request,
			HttpServletResponse response,
			FilterChain         chain)
			throws ServletException, IOException {

		String token = extractBearerToken(request);
		if (StringUtils.hasText(token)) {
			try {
				Claims claims = tokenValidator.validateAndExtract(token);
				setAuthentication(claims, request);
			} catch (JwtException e) {
				log.debug("JWT validation failed: {}", e.getMessage());
				SecurityContextHolder.clearContext();
			}
		}
		chain.doFilter(request, response);
	}

	@SuppressWarnings("unchecked")
	private void setAuthentication(Claims claims, HttpServletRequest request) {
		List<String> roles = claims.get(PaymentConstants.JWT_CLAIM_ROLES, List.class);

		UserPrincipal principal = new UserPrincipal(
				claims.getSubject(),
				claims.get(PaymentConstants.JWT_CLAIM_EMAIL,      String.class),
				claims.get(PaymentConstants.JWT_CLAIM_KYC_STATUS, String.class),
				roles != null ? roles : List.of()
		);

		UsernamePasswordAuthenticationToken auth =
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
		auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
		SecurityContextHolder.getContext().setAuthentication(auth);
	}

	private String extractBearerToken(HttpServletRequest request) {
		String header = request.getHeader("Authorization");
		if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
			return header.substring(7);
		}
		return null;
	}
}
