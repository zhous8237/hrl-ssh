package com.assh.ui.terminal

/**
 * 终端键编码（C7 深化，纯函数）：把"字母 → 控制字符 / Alt → ESC 前缀"这类终端协议逻辑
 * 从 [TerminalViewModel]（sticky-key）和 [TerminalScreen] 的 ^X 网格里收到一处。
 *
 * 此前两处各有一套公式（VM 用 `c-64` 限定在 '@'..'_'，Screen 用 `code and 0x1F`），
 * 边界（^@ / ^\ / 空格）行为会漂移且都无法单测。现在统一在这里，可纯字符串单测。
 */
object KeyEncoder {

    /**
     * 把一个字母/字符编码成它的控制字符（Ctrl+X）。
     * 规则：取大写后 `code and 0x1F`，覆盖 @A-Z[\]^_ → 0x00..0x1F（与终端约定一致）。
     */
    fun ctrl(ch: Char): Char = Char(ch.uppercaseChar().code and 0x1F)

    /** ^X 形态的标签（如 "^C"、"^\\"）→ 对应控制字符串。非该形态原样返回。 */
    fun ctrlLabel(label: String): String =
        if (label.length == 2 && label[0] == '^') ctrl(label[1]).toString() else label

    /** Alt 前缀：ESC + 原文 */
    fun alt(s: String): String = Char(27) + s

    /**
     * 应用粘滞键：Ctrl 把单字符转控制字符（空格→NUL），Alt 加 ESC 前缀。
     * 与旧 [TerminalViewModel.applyStickyToChar] 行为对齐，但作为纯函数可独立测试。
     */
    fun applySticky(text: String, ctrlActive: Boolean, altActive: Boolean): String {
        var out = text
        if (ctrlActive && text.length == 1) {
            val c = text[0].uppercaseChar()
            out = when {
                c == ' ' -> Char(0).toString()
                c in '@'..'_' -> ctrl(c).toString()
                else -> text
            }
        }
        if (altActive) out = alt(out)
        return out
    }
}
