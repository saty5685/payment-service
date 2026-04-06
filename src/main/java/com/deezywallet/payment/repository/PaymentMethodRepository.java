package com.deezywallet.payment.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.deezywallet.payment.entity.PaymentMethod;
import com.deezywallet.payment.enums.PaymentGatewayEnum;

/**
 * Payment method (token vault) repository.
 *
 * SOFT DELETE:
 *   All queries filter by isActive = true.
 *   Deleting a method sets isActive = false — never hard-deletes.
 *   Historical payment rows reference paymentMethodId; hard-delete would
 *   break FK integrity and lose audit trail.
 *
 * DEFAULT METHOD:
 *   At most one method per user has isDefault = true.
 *   Enforced at application layer (not DB UNIQUE constraint) to allow
 *   atomic default-switching without a constraint violation window.
 *
 * findByUserIdAndGatewayAndGatewayToken:
 *   Used during payment method creation to detect duplicates.
 *   If the user submits the same card token twice (same gateway_token),
 *   we return the existing method instead of creating a duplicate.
 */
@Repository
public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, String> {

	// ── Active methods for a user ─────────────────────────────────────────────

	List<PaymentMethod> findByUserIdAndIsActiveTrueOrderByIsDefaultDescCreatedAtDesc(
			String userId);

	Optional<PaymentMethod> findByIdAndUserIdAndIsActiveTrue(String id, String userId);

	Optional<PaymentMethod> findByUserIdAndIsDefaultTrueAndIsActiveTrue(String userId);

	// ── Duplicate detection ───────────────────────────────────────────────────

	Optional<PaymentMethod> findByUserIdAndGatewayAndGatewayTokenAndIsActiveTrue(
			String userId, PaymentGatewayEnum gateway, String gatewayToken);

	// ── Default management ────────────────────────────────────────────────────

	/**
	 * Clears the default flag on all active methods for a user.
	 * Called before setting a new default — ensures only one default exists.
	 *
	 * WHY a bulk UPDATE instead of load-and-save?
	 *   A user may have 3-5 active methods. Loading all, clearing flags, and
	 *   saving each would be 3-5 SELECT + 3-5 UPDATE round-trips.
	 *   A single bulk UPDATE is one round-trip regardless of method count.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
           UPDATE PaymentMethod pm
           SET pm.isDefault = false
           WHERE pm.userId = :userId AND pm.isActive = true
           """)
	void clearDefaultForUser(@Param("userId") String userId);

	// ── Soft delete ───────────────────────────────────────────────────────────

	@Modifying(clearAutomatically = true)
	@Query("""
           UPDATE PaymentMethod pm
           SET pm.isActive = false
           WHERE pm.id = :id AND pm.userId = :userId AND pm.isActive = true
           """)
	int softDeleteByIdAndUserId(@Param("id") String id, @Param("userId") String userId);

	// ── Count (for limiting methods per user) ─────────────────────────────────

	long countByUserIdAndIsActiveTrue(String userId);
}
