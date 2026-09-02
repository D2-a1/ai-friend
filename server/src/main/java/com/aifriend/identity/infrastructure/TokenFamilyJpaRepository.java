package com.aifriend.identity.infrastructure;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Token family Spring Data Repository。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface TokenFamilyJpaRepository extends JpaRepository<TokenFamilyEntity, UUID> {
}
