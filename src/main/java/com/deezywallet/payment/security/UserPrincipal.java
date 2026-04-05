package com.deezywallet.payment.security;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Authenticated principal for Payment Service requests.
 *
 * kycStatus is included because Payment Service enforces KYC requirements
 * for higher top-up amounts (> ₹10,000) without an extra User Service call.
 * Same pattern as Transaction Service.
 */
@Getter
@AllArgsConstructor
public class UserPrincipal implements UserDetails {

	private final String       userId;
	private final String       email;
	private final String       kycStatus;
	private final List<String> roles;

	public boolean isKycVerified() {
		return "VERIFIED".equals(kycStatus);
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return roles.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList());
	}

	@Override public String  getPassword()             { return null; }
	@Override public String  getUsername()             { return email; }
	@Override public boolean isAccountNonExpired()     { return true; }
	@Override public boolean isAccountNonLocked()      { return true; }
	@Override public boolean isCredentialsNonExpired() { return true; }
	@Override public boolean isEnabled()               { return true; }
}
