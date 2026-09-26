package me.rerere.rikkahub.data.mobileagent

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

data class PhoneTargetApp(val packageName: String, val label: String)

/** Local package metadata only; the installed-app list is never a model tool result. */
class PhoneTargetAppRepository(private val context: Context, private val backend: PhoneBackend) {
    suspend fun load(): List<PhoneTargetApp> = withContext(Dispatchers.IO) {
        val manager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        manager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo?.enabled == true }
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == context.packageName || !backend.isTargetAllowed(packageName)) return@mapNotNull null
                PhoneTargetApp(packageName, info.loadLabel(manager).toString().ifBlank { packageName })
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy<PhoneTargetApp> { normalize(it.label) }.thenBy { it.packageName })
    }

    fun resolve(appName: String, apps: List<PhoneTargetApp>): List<PhoneTargetApp> = Companion.resolve(appName, apps)

    companion object {
        // Explicit equivalences only. In particular, "browser" does not select Chrome
        // (or the first installed browser), and partial package/name matching is absent.
        private val aliases = listOf(
            setOf("计算器", "calculator"),
            setOf("日历", "calendar"),
            setOf("时钟", "clock"),
            setOf("相机", "camera"),
            setOf("相册", "图库", "gallery", "photos"),
            setOf("微信", "wechat"),
            setOf("谷歌浏览器", "google chrome", "chrome"),
            setOf("火狐浏览器", "mozilla firefox", "firefox"),
            setOf("微软edge", "microsoft edge", "edge"),
        )

        fun resolve(appName: String, apps: List<PhoneTargetApp>): List<PhoneTargetApp> {
            val requested = normalize(appName)
            if (requested.isBlank()) return emptyList()
            val distinctApps = apps.distinctBy { it.packageName }
            val exact = distinctApps.filter { normalize(it.label) == requested || normalize(it.packageName) == requested }
            if (exact.isNotEmpty()) return exact
            val equivalents = aliases.firstOrNull { requested in it } ?: return emptyList()
            return distinctApps.filter { normalize(it.label) in equivalents }
        }

        /** Auto-start also needs evidence that the current user actually named this app. */
        fun isMentionedIn(text: String, app: PhoneTargetApp): Boolean {
            val value = normalize(text)
            val label = normalize(app.label)
            val names = setOf(label, normalize(app.packageName)) + aliases.firstOrNull { label in it }.orEmpty()
            return names.filter { it.isNotBlank() }.any { name ->
                var position = value.indexOf(name)
                while (position >= 0) {
                    val before = value.substring(0, position)
                    val after = value.substring(position + name.length)
                    val leftBoundary = before.isEmpty() || isPunctuation(before.last()) ||
                        targetPrefix.containsMatchIn(before)
                    val remaining = after.trimStart()
                    val rightBoundary = remaining.isEmpty() || isPunctuation(remaining.first()) ||
                        targetSuffix.containsMatchIn(remaining)
                    val partOfDottedName = before.endsWith('.') ||
                        (after.startsWith('.') && after.getOrNull(1)?.isLetterOrDigit() == true)
                    if (leftBoundary && rightBoundary && !partOfDottedName) return@any true
                    position = value.indexOf(name, position + 1)
                }
                false
            }
        }

        private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

        private fun isPunctuation(character: Char): Boolean =
            character in "，,。.!！?？;；:：、()（）[]【】\"'“”‘’「」"

        private val targetPrefix = Regex("(?:打开|启动|进入|点开|用|使用|在|到|从|切换到|切到|操作|控制|帮我|替我|\\bopen|\\blaunch|\\bin|\\busing|\\buse|\\bvia|\\bswitch to|\\boperate|\\bcontrol)\\s*$")
        private val targetSuffix = Regex("^(?:里面|里|中|上|内|并|然后|再|帮我|给我|为我|看看|看一下|搜索|查找|点击|输入|计算|算|完成|吗|好吗|(?:and|then|to|please)\\b)")
    }
}
