package me.rerere.rikkahub.data.mobileagent

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneIntentGuardTest {
    @Test fun `ordinary questions and capability questions cannot grant control`() {
        assertAll(PhoneIntentPermission.INFORMATIONAL,
            "计算器怎么打开？", "如何打开微信", "你能告诉我怎样打开计算器吗？",
            "请解释一下手机为什么发热", "你能操作手机吗？", "Can you control my phone?",
            "打开微信是什么意思？", "打开微信会怎么样？", "打开微信是否安全？",
        )
    }

    @Test fun `ordinary information tasks do not become device tasks`() {
        assertAll(PhoneIntentPermission.INFORMATIONAL,
            "算一下 12×34", "帮我计算12乘34", "请翻译这段英文", "帮我查一下北京天气",
            "你好", "今天很热", "", "What is Android?", "Please calculate 12 * 34",
        )
    }

    @Test fun `direct natural action requests including polite questions are explicit`() {
        assertAll(PhoneIntentPermission.EXPLICIT_ACTION,
            "打开计算器，计算 12×34", "请帮我打开微信", "能帮我打开计算器，算一下12×34吗？",
            "能不能帮我打开日历", "麻烦现在打开相机", "点击第二个按钮", "长按这个按钮",
            "在计算器里计算12乘34", "Please open Calculator", "Can you open Calculator?",
            "Could you help me open Calculator?", "ＯＰＥＮ Calculator",
        )
    }

    @Test fun `negated requests cannot grant control`() {
        assertAll(PhoneIntentPermission.INFORMATIONAL,
            "不要打开微信", "请不要打开计算器", "别操作手机", "不需要打开任何应用",
            "不能打开日历", "你不能帮我打开计算器",
            "不要打开微信，告诉我怎么设置通知", "Do not open Calculator", "Don't open WeChat",
        )
    }

    @Test fun `quotes and hypotheticals cannot turn examples into permission`() {
        assertAll(PhoneIntentPermission.INFORMATIONAL,
            "解释一下‘打开微信’", "“打开微信”", "`打开计算器`", "```打开微信```",
            "如果我说打开微信，你会怎么做", "假设打开微信", "What if I say open Calculator?",
            "翻译：\"Open Calculator\"", "说明：打开微信",
        )
    }

    @Test fun `explicit requests to execute quoted commands need confirmation`() {
        assertAll(PhoneIntentPermission.CONFIRM,
            "执行这句：‘打开计算器’", "请打开‘计算器’", "请执行这段代码：`open Calculator`",
            "打开“计算器", "Please execute 'open Calculator'",
        )
    }

    @Test fun `mixed negatives requests and questions remain confirmation only`() {
        assertAll(PhoneIntentPermission.CONFIRM,
            "打开计算器，但不要清空已有结果", "不要告诉我怎么做，直接打开计算器",
            "不要打开微信，打开计算器", "告诉我计算器怎么用，然后打开计算器",
            "打开微信，然后告诉我怎么设置通知", "打开计算器还是不要打开？",
            "能不能帮我打开日历，但不能修改日程", "打开计算器，但不能帮我清空结果",
            "Open Calculator but do not clear its results",
        )
    }

    @Test fun `conditions references and unsupported phrasing do not silently authorize`() {
        assertAll(PhoneIntentPermission.CONFIRM,
            "如果可以，请打开计算器", "我想让你打开微信", "操作刚才那个应用",
            "继续刚才的任务", "这个手机应用", "If available, open Calculator",
        )
    }

    @Test fun `very long instructions require confirmation without partial parsing`() {
        assertEquals(PhoneIntentPermission.CONFIRM, PhoneIntentGuard.classify("打开计算器" + "x".repeat(16_000)))
    }

    private fun assertAll(expected: PhoneIntentPermission, vararg samples: String) {
        samples.forEach { assertEquals(it, expected, PhoneIntentGuard.classify(it)) }
    }
}
