package me.rerere.rikkahub.data.mobileagent

import java.text.Normalizer
import java.util.Locale

/**
 * Only recognizes a conservative shortcut for opening one app. False/null never rejects
 * a model proposal: unfamiliar language and multi-step requests require user confirmation.
 */
object PhoneIntentGuard {
    fun canStartAutomatically(text: String): Boolean = automaticTargetName(text) != null

    /** The caller must resolve this entire name locally and match the model's single target. */
    fun automaticTargetName(text: String): String? {
        if (text.isBlank() || text.length > 256) return null
        val value = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()
        if (value.any { it in ",;:\n\r`\"'“”‘’「」『』" } || negation.containsMatchIn(value) ||
            conditional.containsMatchIn(value) || informationQuestion.containsMatchIn(value) ||
            referentialTarget.containsMatchIn(value)
        ) return null
        val chinese = chineseOpen.matchEntire(value)
        if (chinese != null) return target(chinese.groupValues[1])
        val english = englishOpen.matchEntire(value) ?: return null
        if (value.endsWith('?') && !politeEnglishQuestion.containsMatchIn(value)) return null
        return target(english.groupValues[1])
    }

    private fun target(value: String): String? = value.trim().takeIf { it.length in 1..128 }

    // Capture the whole remaining app name, not a substring of a larger request. A
    // phrase such as "微信发消息" will not resolve to the installed app named "微信".
    private val appName = "([\\p{L}\\p{N}][\\p{L}\\p{N} ._()\\-]*?)"
    private val chinesePrefix = "(?:(?:请|麻烦|劳驾|现在|直接|马上|先|帮我|替我|给我|为我|帮忙|能不能帮我|可以帮我|能帮我|能否帮我|请你|请帮我)\\s*){0,4}"
    private val chineseOpen = Regex("^$chinesePrefix(?:打开|启动|点开|进入)\\s*$appName(?:吗[?？]?|[。!！])?$")
    private val englishOpen = Regex(
        "^(?:(?:please|could you|can you|would you|help me|please help me|could you help me|can you help me)\\s+){0,4}" +
            "(?:open|launch)\\s+$appName[.!?]?$",
    )
    private val politeEnglishQuestion = Regex("^(?:please\\s+)?(?:can you|could you|would you)\\b")
    // "能不能帮我" is a polite request; other negatives never get this shortcut.
    private val negation = Regex("不要|别再|不用|无需|不需要|禁止|不许|不想|不必|(?<!能)不能|do not\\b|don't\\b|never\\b")
    private val conditional = Regex("如果|假如|若是|要是|假设|假定|\\bif\\b|\\bunless\\b")
    private val informationQuestion = Regex("怎么|怎样|如何|为什么|为何|是什么|什么是|是否|会不会|有没有|哪里|哪儿|有何|意味着|什么意思|的含义|安全吗|会怎样|会怎么样|\\b(?:how|why|what|where|whether)\\b")
    private val referentialTarget = Regex("刚才|之前|那个应用|这个应用|那个app|这个app|操作它|打开它|\\b(?:that app|this app|previous app)\\b")
}
