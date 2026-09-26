package me.rerere.rikkahub.data.mobileagent

import java.text.Normalizer
import java.util.Locale

enum class PhoneIntentPermission { INFORMATIONAL, EXPLICIT_ACTION, CONFIRM }

/**
 * A conservative local permission check, not a language-understanding replacement.
 * The chat model proposes intent; only a narrow direct-request subset may start without
 * another confirmation. Uncertain phrasing stays in the chat for the user to confirm.
 */
object PhoneIntentGuard {
    fun classify(text: String): PhoneIntentPermission {
        if (text.isBlank()) return PhoneIntentPermission.INFORMATIONAL
        if (text.length > 16_000) return PhoneIntentPermission.CONFIRM
        val value = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()

        // A command inside a quotation/document is data. An explicit request outside the
        // quotation may still be actionable, but requires a local confirmation.
        val unquoted = quoted.replace(value, " ").trim()
        if (unquoted != value) {
            return if (directRequest.containsMatchIn(unquoted) || executeQuoted.containsMatchIn(unquoted)) {
                PhoneIntentPermission.CONFIRM
            } else PhoneIntentPermission.INFORMATIONAL
        }

        if (hypotheticalDiscussion.containsMatchIn(value)) return PhoneIntentPermission.INFORMATIONAL
        val hasDirectRequest = directRequest.containsMatchIn(value)
        val clauses = value.split(clauseBoundary).filter { it.isNotBlank() }
        val hasLaterRequest = clauses.drop(1).any { directRequest.containsMatchIn(it.trim()) }

        if (capabilityQuestion.containsMatchIn(value) || actionTopicQuestion.containsMatchIn(value)) {
            return if (hasLaterRequest || (hasDirectRequest && clauses.size > 1)) {
                PhoneIntentPermission.CONFIRM
            } else PhoneIntentPermission.INFORMATIONAL
        }

        if (negativeRequest.containsMatchIn(value)) {
            return if (hasLaterRequest || positiveOverride.containsMatchIn(value)) {
                PhoneIntentPermission.CONFIRM
            } else PhoneIntentPermission.INFORMATIONAL
        }
        if (informationalOpening.containsMatchIn(value)) {
            return if (hasLaterRequest) PhoneIntentPermission.CONFIRM else PhoneIntentPermission.INFORMATIONAL
        }
        if (conditional.containsMatchIn(value) || referentialTarget.containsMatchIn(value)) return PhoneIntentPermission.CONFIRM
        if (hasDirectRequest) {
            return if (negation.containsMatchIn(value) || contradictoryQuestion.containsMatchIn(value) ||
                informationQuestion.containsMatchIn(value) || value.any { it in "`\"“”‘’「」『』" }
            ) {
                PhoneIntentPermission.CONFIRM
            } else PhoneIntentPermission.EXPLICIT_ACTION
        }
        if (informationQuestion.containsMatchIn(value) || ordinaryInformationTask.containsMatchIn(value)) {
            return PhoneIntentPermission.INFORMATIONAL
        }
        // These may be real requests with omitted targets or unfamiliar wording. The
        // model cannot turn that uncertainty into permission; the user gets a chat card.
        if (actionWords.containsMatchIn(value) || deviceWords.containsMatchIn(value)) {
            return PhoneIntentPermission.CONFIRM
        }
        return PhoneIntentPermission.INFORMATIONAL
    }

    private val quoted = Regex("```[\\s\\S]*?```|`[^`]*`|\"[^\"]*\"|'[^']*'|“[^”]*”|‘[^’]*’|「[^」]*」|『[^』]*』")
    private val clauseBoundary = Regex("[，,。.!！?？;；\\n]")
    private val action = "(?:打开|启动|进入|点开|点击|点一下|点按|按下|长按|滑动|滑到|滚动|上滑|下滑|左滑|右滑|输入|填写|粘贴|切换到|切到|返回|关闭|选中|勾选|取消勾选|操作|控制)"
    private val requestPrefix = "(?:(?:请|麻烦|劳驾|现在|直接|马上|先|然后|再|接着|帮我|替我|给我|为我|帮忙|帮我把|替我把|能不能帮我|可以帮我|能帮我|能否帮我|请你|请帮我)\\s*){0,4}"
    private val directRequest = Regex(
        "^(?:$requestPrefix$action|$requestPrefix(?:用|使用|在).{1,40}(?:里|中|上|内)?(?:打开|点击|搜索|查找|输入|计算|算一下|算出|操作)|" +
            "(?:(?:please|could you|can you|would you|help me|please help me|could you help me|can you help me)\\s+){0,4}(?:open|launch|tap|click|long[- ]press|swipe|scroll|type into|enter into|switch to|operate|control)\\b)",
    )
    private val executeQuoted = Regex("^(?:(?:请|帮我|直接|现在)\\s*){0,4}(?:执行|照做|按.{0,8}做)|^(?:please\\s+)?(?:execute|carry out)\\b")
    private val negativeRequest = Regex("^(?:(?:请|你|先|暂时)\\s*){0,4}(?:不要|别|不用|无需|不需要|不能|禁止|不许|不想|不必|先不|暂时不)|^(?:please\\s+)?(?:do not|don't|never|no need to|do not need to)\\b")
    private val positiveOverride = Regex("(?:而是|改为|改成|但请|但是请|直接)(?:请|帮我|替我)?$action")
    // "能不能帮我…" is a polite request; standalone "不能" still limits permission.
    private val negation = Regex("不要|别再|不用|无需|不需要|禁止|不许|不想|不必|(?<!能)不能|do not\\b|don't\\b|never\\b")
    private val hypotheticalDiscussion = Regex("(?:如果|假如|假设|假定).{0,35}(?:我说|我让|我要求|你会|会不会)|^(?:假设|假定)|\\bwhat if\\b|\\bsuppose\\b|\\bif i (?:say|ask)\\b")
    private val capabilityQuestion = Regex("^(?:你|这个app|这个软件|本应用)(?:能|可以|支持)(?:操作|控制)手机(?:吗|么|不)|^can you (?:operate|control) (?:a |the |my )?phone\\??$")
    private val actionTopicQuestion = Regex("(?:是什么意思|意味着什么|是什么命令|是什么操作|会怎样|会怎么样|安全吗|是否安全|是不是).*[?？]?$|(?:什么意思|的含义)[?？]?$", RegexOption.IGNORE_CASE)
    private val conditional = Regex("如果|假如|若是|要是|假设|假定|\\bif\\b|\\bunless\\b")
    private val referentialTarget = Regex("刚才|之前|那个应用|这个应用|那个app|这个app|操作它|打开它|\\b(?:that app|this app|previous app)\\b")
    private val informationalOpening = Regex(
        "^(?:(?:请|帮我|能不能|能否|可以|你能|请你)\\s*){0,4}(?:告诉我|解释|说明|介绍|讲讲|分析|教我|问一下|请问|我想知道|我只是问|只是问)|" +
            "^(?:(?:please|can you|could you|would you)\\s+){0,4}(?:explain|tell me|teach me|describe|summarize)\\b",
    )
    private val informationQuestion = Regex("怎么|怎样|如何|为什么|为何|是什么|什么是|是否|会不会|有没有|哪里|哪儿|有何|\\b(?:how|why|what|where|whether)\\b")
    private val contradictoryQuestion = Regex("(?:还是|或者).{0,20}(?:不打开|不要|怎么|如何)|(?:是否|会不会).{0,15}(?:危险|出错)|\\bor should\\b")
    private val ordinaryInformationTask = Regex("^(?:(?:请|帮我|麻烦)\\s*){0,4}(?:计算|算一下|算算|翻译|总结|解释|写一|写个|查一下|查查)|^(?:please\\s+)?(?:calculate|translate|summarize|write|explain)\\b")
    private val actionWords = Regex("$action|搜索|查找|找到|选择|执行|继续|恢复|暂停|停止|\\b(?:open|launch|tap|click|swipe|scroll|resume|stop|pause|execute)\\b")
    private val deviceWords = Regex("手机|应用|软件|这个app|那个app|\\b(?:phone|app|application)\\b")
}
