package me.rerere.rikkahub.data.mobileagent

/** Reading a price or checkout summary does not authorize the final account-changing button. */
object PhonePurchasePolicy {
    private val userOnly = listOf(
        "确认付款", "确认支付", "立即支付", "立即付款", "提交订单", "确认下单", "立即下单",
        "提交购买", "一键购买", "免密支付", "开通会员", "立即开通", "付费开通", "自动续费",
        "去支付", "去付款", "确认并支付", "下单并支付", "确认购买", "支付订单", "立即兑换",
        "免费试用", "绑定服务", "积分抵扣", "使用积分", "消耗积分", "余额支付", "储值支付",
        "paynow", "placeorder", "submitorder", "confirmpayment", "startfreetrial", "subscribeandpay",
    )

    fun requiresUser(text: String): Boolean {
        val normalized = text.filterNot { it.isWhitespace() || it == '\u200B' || it == '\uFEFF' }.lowercase()
        return userOnly.any(normalized::contains) ||
            Regex("(?:支付|付款|pay)[¥￥$]?\\d+(?:\\.\\d{1,2})?").containsMatchIn(normalized) ||
            ("积分" in normalized && listOf("抵扣", "兑换", "消耗", "使用").any(normalized::contains))
    }
}
