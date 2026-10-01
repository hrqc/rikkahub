package me.rerere.rikkahub.data.mobileagent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Fixed rejection metadata shared by primitive and composed phone tools. Never includes page text. */
internal fun phoneSnapshotRejectionJson(rejection: PhoneSnapshotRejection): JsonObject = buildJsonObject {
    put("reason", rejection.reason.name)
    put("age_ms", rejection.ageMillis)
    put("revision_changed", rejection.revisionChanged)
    put("window_changed", rejection.windowChanged)
    rejection.clickRevalidation?.let { click ->
        put("click_revalidation", buildJsonObject {
            put("stage", click.stage.name)
            put("anchor_present", click.anchorPresent)
            put("candidate_count", click.candidateCount)
            put("eligible_count", click.eligibleCount)
            put("target_proof_reason", click.targetProofReason?.name?.let(::JsonPrimitive) ?: JsonNull)
            put("proof_reasons", JsonArray(click.proofReasons.map { JsonPrimitive(it.name) }))
            put("last_clear_reason", click.lastClearReason?.name?.let(::JsonPrimitive) ?: JsonNull)
            put("last_clear_event", click.lastClearEvent?.name?.let(::JsonPrimitive) ?: JsonNull)
        })
    }
}
