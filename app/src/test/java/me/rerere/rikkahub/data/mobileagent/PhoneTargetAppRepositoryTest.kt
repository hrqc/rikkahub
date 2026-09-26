package me.rerere.rikkahub.data.mobileagent

import org.junit.Assert.*
import org.junit.Test

class PhoneTargetAppRepositoryTest {
    private val calculator = PhoneTargetApp("com.example.calc", "计算器")
    private val chrome = PhoneTargetApp("com.example.chrome", "Chrome")
    private val wechat = PhoneTargetApp("com.example.wechat", "微信")
    private val apps = listOf(calculator, chrome, wechat)

    @Test fun `exact local labels and package names resolve without guessing`() {
        assertEquals(listOf(calculator), PhoneTargetAppRepository.resolve("计算器", apps))
        assertEquals(listOf(chrome), PhoneTargetAppRepository.resolve("  CHROME  ", apps))
        assertEquals(listOf(calculator), PhoneTargetAppRepository.resolve("com.example.calc", apps))
        assertEquals(listOf(chrome), PhoneTargetAppRepository.resolve("Ｃｈｒｏｍｅ", apps))
    }

    @Test fun `limited aliases resolve only to matching installed labels`() {
        assertEquals(listOf(calculator), PhoneTargetAppRepository.resolve("Calculator", apps))
        assertEquals(listOf(wechat), PhoneTargetAppRepository.resolve("WeChat", apps))
        assertEquals(listOf(chrome), PhoneTargetAppRepository.resolve("谷歌浏览器", apps))
        assertTrue(PhoneTargetAppRepository.resolve("火狐浏览器", apps).isEmpty())
    }

    @Test fun `partial labels generic categories and fabricated packages never pick an app`() {
        listOf("计算", "chrom", "浏览器", "browser", "默认浏览器", "com.fake.calculator", "", " ")
            .forEach { assertTrue(it, PhoneTargetAppRepository.resolve(it, apps).isEmpty()) }
    }

    @Test fun `duplicate labels remain ambiguous and duplicate launcher entries are deduplicated`() {
        val second = PhoneTargetApp("com.other.calc", "计算器")
        assertEquals(listOf(calculator, second), PhoneTargetAppRepository.resolve("计算器", listOf(calculator, calculator, second)))
        assertEquals(listOf(calculator, second), PhoneTargetAppRepository.resolve("calculator", listOf(calculator, second)))
    }

    @Test fun `exact labels take priority over translated aliases`() {
        val english = PhoneTargetApp("com.other.calc", "Calculator")
        assertEquals(listOf(english), PhoneTargetAppRepository.resolve("calculator", listOf(calculator, english)))
    }

    @Test fun `current user must name the resolved app or an explicit alias`() {
        assertTrue(PhoneTargetAppRepository.isMentionedIn("打开计算器，计算12乘34", calculator))
        assertTrue(PhoneTargetAppRepository.isMentionedIn("能帮我打开计算器吗？", calculator))
        assertTrue(PhoneTargetAppRepository.isMentionedIn("在计算器里算一下12", calculator))
        assertTrue(PhoneTargetAppRepository.isMentionedIn("Open Calculator and enter 12", calculator))
        assertTrue(PhoneTargetAppRepository.isMentionedIn("打开谷歌浏览器", chrome))
        assertTrue(PhoneTargetAppRepository.isMentionedIn("打开com.example.calc", calculator))
    }

    @Test fun `app-name substrings and model-invented target do not count as user naming target`() {
        assertFalse(PhoneTargetAppRepository.isMentionedIn("帮我搜天气", chrome))
        assertFalse(PhoneTargetAppRepository.isMentionedIn("打开浏览器", chrome))
        assertFalse(PhoneTargetAppRepository.isMentionedIn("打开微信助手", wechat))
        assertFalse(PhoneTargetAppRepository.isMentionedIn("Open SuperCalculator", calculator))
        assertFalse(PhoneTargetAppRepository.isMentionedIn("Open CalculatorPlus", calculator))
        assertFalse(PhoneTargetAppRepository.isMentionedIn("Open Calculator Plus", calculator))
        assertFalse(PhoneTargetAppRepository.isMentionedIn("Open Super Calculator", calculator))
        assertFalse(PhoneTargetAppRepository.isMentionedIn("打开微信 助手", wechat))
        assertFalse(PhoneTargetAppRepository.isMentionedIn("打开com.example.calc.fake", calculator))
    }
}
