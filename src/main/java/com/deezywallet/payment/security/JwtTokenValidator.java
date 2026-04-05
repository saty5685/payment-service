package com.deezywallet.payment.security;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import com.deezywallet.payment.config.JwtProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;

/** JWT validator — validates only, never issues. Same pattern as other services. */
@Component
@RequiredArgsConstructor
public class JwtTokenValidator {

	private final JwtProperties jwtProperties;

	private SecretKey signingKey() {
		return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.getSecret()));
	}

	public Claims validateAndExtract(String token) {
		return Jwts.parser()
				.verifyWith(signingKey())
				.build()
				.parseSignedClaims(token)
				.getPayload();
	}
}