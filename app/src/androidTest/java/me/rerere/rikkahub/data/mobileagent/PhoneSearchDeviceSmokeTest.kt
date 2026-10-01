package me.rerere.rikkahub.data.mobileagent

import android.os.Bundle
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.UUID

/** Explicit USB-only invocation of the same production search composition; never purchases items. */
@RunWith(AndroidJUnit4::class)
class PhoneSearchDeviceSmokeTest {
    @Test fun submitExplicitJdSearchWithoutShoppingActions(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("必须显式启用本地搜索实测", arguments.getString("runLocalSearch") == "true")
        val encoded = arguments.getString("searchQueryB64") ?: error("缺少明确查询词")
        val query = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        assertTrue("查询词无效", query.isNotBlank() && query.length <= 120 && query.none { it.code < 32 })
        val controller = GlobalContext.get().get<PhoneController>()
        val backend = GlobalContext.get().get<PhoneBackend>()
        val capabilities = GlobalContext.get().get<DeviceCapabilityRepository>()
        val waitMillis = arguments.getString("waitForServiceMillis")?.toLongOrNull()?.coerceIn(1_000, 120_000) ?: 8_000L
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "[LocalSearchSmoke] waiting for authorized service and foreground JD\n") })
        assertTrue("前台或服务未就绪", withTimeoutOrNull(waitMillis) {
            while (!backend.state.value.let { it.connected && !it.locked && it.foregroundPackage == TARGET }) delay(100)
            true
        } == true)
        val useRoot = capabilities.ensureRootForTask()
        val token = controller.start("search-smoke-${UUID.randomUUID()}", "local-search-smoke", TARGET,
            useRoot = useRoot, allowScreenshots = false, replaceExisting = false)
        var result: PhoneSearchResult? = null
        try {
            delay(500)
            result = PhoneSearchWorkflow(controller, token).search(query)
        } finally {
            val state = controller.state.value.takeIf { it.token == token }
            val outcome = result
            val metadata = buildJsonObject {
                put("status", outcome?.status ?: "INTERRUPTED")
                put("errorCode", outcome?.errorCode)
                put("queryConfirmed", outcome?.queryConfirmed ?: false)
                put("useRoot", useRoot)
                put("controllerActionsUsed", state?.actionsUsed)
                put("controllerObservationsUsed", state?.observationsUsed)
                put("nodeCount", outcome?.observation?.nodes?.size)
                put("previewTruncated", outcome?.observation?.previewTruncated)
                put("shoppingTaskCompleted", false)
                put("steps", JsonArray(outcome?.steps.orEmpty().map { step -> buildJsonObject {
                    put("operation", step.operation); put("accepted", step.accepted); put("executor", step.executor?.wireName)
                } }))
                outcome?.snapshotValidation?.let { put("snapshot_validation", it) }
            }.toString()
            try {
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("local_search_result_b64", Base64.encodeToString(metadata.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                })
            } finally {
                controller.stopIfCurrent(token, "本地搜索实测已结束")
            }
        }
        assertEquals("QUERY_SUBMITTED", result?.status)
        assertTrue("不得超过3个动作", result!!.steps.size <= 3)
    }

    companion object { private const val TARGET = "com.jingdong.app.mall" }
}
