package com.aifriend.feature.consent

import com.aifriend.contract.model.Consent
import com.aifriend.contract.model.ConsentDecision
import com.aifriend.contract.model.ConsentType

/**
 * 当前用户分项授权仓库。
 *
 * @author codex
 * @since 2026-08-04
 */
interface ConsentRepository {
    suspend fun listCurrent(): List<Consent>

    suspend fun update(type: ConsentType, decision: ConsentDecision, policyVersion: String): Consent
}
