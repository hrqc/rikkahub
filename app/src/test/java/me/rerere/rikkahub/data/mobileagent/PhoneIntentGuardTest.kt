package me.rerere.rikkahub.data.mobileagent

import org.junit.Assert.*
import org.junit.Test

class PhoneIntentGuardTest {
    @Test fun `simple complete app opening requests expose the exact original target name`() {
        mapOf(
            "打开计算器" to "计算器",
            "请帮我打开微信" to "微信",
            "能不能帮我打开日历" to "日历",
            "能帮我打开计算器吗？" to "计算器",
            "麻烦现在启动相机" to "相机",
            "点开微信。" to "微信",
            "进入日历" to "日历",
            "Please open Calculator" to "calculator",
            "Can you open Calculator?" to "calculator",
            "Could you help me open Calculator?" to "calculator",
            "ＯＰＥＮ Calculator" to "calculator",
        ).forEach { (input, target) ->
            assertTrue(input, PhoneIntentGuard.canStartAutomatically(input))
            assertEquals(input, target, PhoneIntentGuard.automaticTargetName(input))
        }
    }

    @Test fun `multiple clauses and steps always need confirmation`() {
        assertNeedsConfirmation(
            "打开计算器，计算12乘34", "打开微信，发消息要几步？", "打开微信。然后发测试信息",
            "打开微信，然后告诉我怎么设置通知", "打开计算器\n再计算12乘34", "打开微信；点击文件传输助手",
            "Open Calculator, calculate 12 * 34", "打开微信:发送测试信息",
        )
    }

    @Test fun `ordinary questions and capability questions do not start automatically`() {
        assertNeedsConfirmation(
            "计算器怎么打开？", "如何打开微信", "你能告诉我怎样打开计算器吗？",
            "你能操作手机吗？", "Can you control my phone?", "打开微信是什么意思？",
            "打开微信会怎么样？", "打开微信是否安全？", "打开微信？", "Open Calculator?",
        )
    }

    @Test fun `ordinary information tasks do not start automatically`() {
        assertNeedsConfirmation(
            "算一下12乘34", "帮我查一下北京天气", "请翻译这段英文", "你好", "今天很热", "",
            "What is Android?", "Please calculate 12 * 34",
        )
    }

    @Test fun `negative or mixed requests do not start automatically`() {
        assertNeedsConfirmation(
            "不要打开微信", "请不要打开计算器", "不能打开日历", "你不能帮我打开计算器",
            "不要打开微信，打开计算器", "打开计算器，但不能清空结果", "Do not open Calculator",
            "Don't open WeChat", "Open Calculator but do not clear its results",
        )
    }

    @Test fun `quoted commands and hypothetical discussion require confirmation`() {
        assertNeedsConfirmation(
            "解释一下‘打开微信’", "“打开微信”", "`打开计算器`", "```打开微信```",
            "请打开‘计算器’", "打开“计算器", "执行这句：‘打开计算器’", "Please execute 'open Calculator'",
            "如果我说打开微信，你会怎么做", "假设打开微信", "What if I say open Calculator?",
        )
    }

    @Test fun `unfamiliar colloquial requests and typos remain for the model and confirmation`() {
        assertNeedsConfirmation(
            "帮我给微信助手发送测试信息", "邦我给微心助手发测试", "劳驾在薇信那个助手丢条测试呗",
            "麻烦整一下刚刚那个", "达开微新", "帮我给微信文件传输助手发一条消息：Mobile Agent V1 测试",
        )
    }

    @Test fun `retry and references never revive historical authorization automatically`() {
        assertNeedsConfirmation(
            "再试一次", "重试", "请重试一次", "重新尝试一遍", "继续刚才任务", "再来一回",
            "打开刚才那个应用", "打开这个应用", "Open that app",
        )
    }

    @Test fun `non opening gestures and larger tasks require confirmation`() {
        assertNeedsConfirmation(
            "点击第二个按钮", "长按这个按钮", "输入测试信息", "在计算器里计算12乘34",
            "关闭微信", "滑动列表", "Please click the button",
        )
    }

    @Test fun `target extraction never shortens a remaining phrase into an app name`() {
        assertEquals("微信发送测试信息", PhoneIntentGuard.automaticTargetName("打开微信发送测试信息"))
        assertEquals("calculator plus", PhoneIntentGuard.automaticTargetName("Open Calculator Plus"))
        assertEquals("com.tencent.mm", PhoneIntentGuard.automaticTargetName("打开 com.tencent.mm"))
        // These extracted names must still resolve exactly; the coordinator does not
        // treat the mere presence of a familiar substring as automatic permission.
    }

    @Test fun `missing targets and unsupported punctuation do not start automatically`() {
        assertNeedsConfirmation("打开", "启动 ", "Open", "打开微信/计算器", "打开微信或者？")
    }

    @Test fun `very long instructions require confirmation without partial parsing`() {
        assertNeedsConfirmation("打开计算器" + "x".repeat(16_000))
    }

    private fun assertNeedsConfirmation(vararg samples: String) {
        samples.forEach {
            assertFalse(it, PhoneIntentGuard.canStartAutomatically(it))
            assertNull(it, PhoneIntentGuard.automaticTargetName(it))
        }
    }
}
