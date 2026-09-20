package com.aifriend.feature.wechat

/** 只分离浅灰搜索图形，不按期望账号删字、补字或模糊匹配。 */
object WechatSearchQueryArtworkRule {
    fun separate(
        line: WechatVisualTextLine,
        parts: List<WechatVisualTextLine>,
        artworkMinimumChannel: Int,
        queryDarkPixels: Int,
    ): WechatVisualTextLine {
        if (parts.size != 2) return line
        val (artwork, query) = parts
        val height = query.bottom - query.top
        if (artwork.text.length != 1 || line.text.trim() != "${artwork.text} ${query.text}" ||
            artwork.right >= query.left || artwork.right - artwork.left <= 0 ||
            (artwork.right - artwork.left) * 2 >= height || height <= 0 ||
            minOf(artwork.bottom, query.bottom) <= maxOf(artwork.top, query.top) ||
            artworkMinimumChannel !in 140..230 || queryDarkPixels < height) return line
        val parsed = WechatLocalVerificationTextRule.standaloneLocator(query.text) ?: return line
        return try {
            if (parsed.concatToString() == query.text) query else line
        } finally { parsed.fill('\u0000') }
    }
}
