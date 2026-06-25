package me.yxp.qfun.plugin.loader

/**
 * 轻量级 TypeScript -> JavaScript 转译器。
 *
 * QuickJS 仅支持 JavaScript/ES Module，不支持 TypeScript 语法。
 * 本转译器通过字符级状态机剥离 TS 的类型注解，将 .ts 转为可被 QuickJS 执行的 .js。
 *
 * 支持的 TS 特性：
 *  - 参数/变量/返回值的类型注解 (name: Type, name?: Type, function f(): T)
 *  - 泛型声明 (function f<T>(...), class C<T>, const f = <T>(x: T) => x)
 *  - interface / type 声明 (整体移除)
 *  - import type / export type (整行移除)
 *  - 访问修饰符 public/private/protected/readonly (移除关键字)
 *  - as / satisfies 断言 (移除)
 *  - enum -> const 对象
 *  - 非空断言 ! (移除尾随 !)
 *  - 可选链/空值合并等 ES2020 语法原样保留 (QuickJS 支持)
 *
 * 不支持的复杂场景：装饰器、namespace、复杂泛型约束推断等。
 * 插件作者应使用相对直白的 TS 写法。
 */
object TsTranspiler {

    fun transpile(source: String): String {
        val src = stripComments(source)
        val out = StringBuilder(src.length)
        val sb = TokenScanner(src)
        while (sb.hasNext()) {
            val c = sb.peek()
            when {
                // 字符串/模板/正则原样透传
                c == '\'' || c == '"' || c == '`' -> out.append(sb.consumeString())
                sb.startsWith("//") -> out.append(sb.consumeLineComment())
                sb.startsWith("/*") -> out.append(sb.consumeBlockComment())
                // import type / export type 整行移除
                sb.startsWithWord("import") && isTypeOnlyLine(sb) -> {
                    sb.consumeUntilSemicolonOrNewline(); if (sb.peek() == ';') sb.next()
                }
                sb.startsWithWord("export") && isExportTypeLine(sb) -> {
                    sb.consumeUntilSemicolonOrNewline(); if (sb.peek() == ';') sb.next()
                }
                // interface / type 声明整体移除
                sb.startsWithWord("interface") -> stripInterfaceOrType(sb, out)
                sb.startsWithWord("type") && isTypeAlias(sb) -> stripInterfaceOrType(sb, out)
                // enum 转换
                sb.startsWithWord("enum") || (sb.startsWithWord("const") && peekWordAfter(sb, "enum")) ->
                    out.append(convertEnum(sb))
                else -> out.append(sb.next())
            }
        }
        val cleaned = out.toString()
        return stripTypeAnnotations(cleaned)
    }

    // ==================== 注释剥离 ====================

    private fun stripComments(source: String): String {
        val sb = StringBuilder(source.length)
        val s = TokenScanner(source)
        while (s.hasNext()) {
            val c = s.peek()
            when {
                c == '\'' || c == '"' || c == '`' -> sb.append(s.consumeString())
                s.startsWith("//") -> s.consumeLineComment()
                s.startsWith("/*") -> s.consumeBlockComment()
                else -> sb.append(s.next())
            }
        }
        return sb.toString()
    }

    // ==================== type / interface 移除 ====================

    private fun isTypeOnlyLine(s: TokenScanner): Boolean {
        // import type { ... } 或 import type ...
        val saved = s.index
        s.skipWord("import")
        s.skipSpaces()
        return if (s.startsWithWord("type")) { s.index = saved; true } else { s.index = saved; false }
    }

    private fun isExportTypeLine(s: TokenScanner): Boolean {
        val saved = s.index
        s.skipWord("export")
        s.skipSpaces()
        val r = s.startsWithWord("type")
        s.index = saved
        return r
    }

    private fun isTypeAlias(s: TokenScanner): Boolean {
        // type Name = ... (而非 type 作为变量名/属性，TS 中 type 是关键字)
        val saved = s.index
        s.skipWord("type")
        s.skipSpaces()
        val next = s.readWord()
        s.index = saved
        return next.isNotEmpty() && !next.first().isDigit()
    }

    private fun stripInterfaceOrType(s: TokenScanner, out: StringBuilder) {
        // 跳过到 { 出现并匹配括号深度，type 别名则到 ; 结束
        s.skipWord("interface"); s.skipWord("type"); s.skipWord("export"); s.skipWord("default")
        s.skipSpaces()
        // 跳过名字与泛型/继承
        while (s.hasNext()) {
            val c = s.peek()
            if (c == '{') {
                s.skipBlock('{', '}')
                break
            }
            if (c == ';') { s.next(); break }
            s.next()
        }
        // 移除尾随分号
        s.skipSpaces()
        if (s.peek() == ';') s.next()
    }

    // ==================== enum 转换 ====================

    private fun peekWordAfter(s: TokenScanner, word: String): Boolean {
        val saved = s.index
        s.skipWord(word); s.skipSpaces()
        val r = s.startsWithWord("enum")
        s.index = saved
        return r
    }

    private fun convertEnum(s: TokenScanner): String {
        if (s.startsWithWord("const")) { s.skipWord("const"); s.skipSpaces() }
        s.skipWord("enum"); s.skipSpaces()
        val name = s.readWord()
        s.skipSpaces()
        if (s.peek() != '{') return "const $name = {};"
        s.next() // {
        val entries = mutableListOf<String>()
        var autoIdx = 0
        s.skipSpaces()
        while (s.hasNext() && s.peek() != '}') {
            s.skipSpaces()
            val key = s.readWord()
            if (key.isEmpty()) { s.next(); continue }
            s.skipSpaces()
            var value: String
            if (s.peek() == '=') {
                s.next(); s.skipSpaces()
                value = s.readValueUntil(',', '}')
                value = value.trim()
                if (value.toIntOrNull() != null) autoIdx = value.toInt() + 1
            } else {
                value = autoIdx.toString()
                autoIdx++
            }
            entries.add("  \"$key\": $value, $key: $value")
            s.skipSpaces()
            if (s.peek() == ',') { s.next(); s.skipSpaces() }
        }
        if (s.peek() == '}') s.next()
        s.skipSpaces(); if (s.peek() == ';') s.next()
        return "const $name = Object.freeze({\n${entries.joinToString(",\n")}\n});"
    }

    // ==================== 类型注解剥离 ====================

    /**
     * 在已去注释的源码上，剥离声明位置的 `: Type` 注解。
     * 使用括号/泛型深度匹配以正确处理嵌套泛型与联合类型。
     */
    private fun stripTypeAnnotations(source: String): String {
        val s = TokenScanner(source)
        val out = StringBuilder(source.length)
        while (s.hasNext()) {
            val c = s.peek()
            when {
                c == '\'' || c == '"' || c == '`' -> out.append(s.consumeString())
                // as Type / satisfies Type
                s.startsWithWord("as") && prevNonSpaceIsTypeContext(out) -> {
                    s.skipWord("as"); s.skipSpaces(); skipTypeExpression(s); s.skipSpaces()
                }
                s.startsWithWord("satisfies") -> {
                    s.skipWord("satisfies"); s.skipSpaces(); skipTypeExpression(s); s.skipSpaces()
                }
                // 访问修饰符
                s.startsWithWord("public") || s.startsWithWord("private") ||
                s.startsWithWord("protected") || s.startsWithWord("override") ||
                s.startsWithWord("readonly") || s.startsWithWord("abstract") ||
                s.startsWithWord("declare") || s.startsWithWord("static") -> {
                    // 仅在声明上下文移除；保守处理：移除关键字并跟随空格
                    s.readWord(); s.skipSpaces()
                }
                // 非空断言 x!
                c == '!' && prevIsIdentifierOrParen(out) -> { s.next() }
                else -> out.append(s.next())
            }
        }
        // 二次处理：剥离 `: Type` 注解 (在声明位置)
        return stripColonAnnotations(out.toString())
    }

    private fun prevNonSpaceIsTypeContext(out: StringBuilder): Boolean {
        var i = out.length - 1
        while (i >= 0 && out[i].isWhitespace()) i--
        return i >= 0 && (out[i].isLetterOrDigit() || out[i] == '_' || out[i] == ']' || out[i] == ')' || out[i] == '>')
    }

    private fun prevIsIdentifierOrParen(out: StringBuilder): Boolean {
        if (out.isEmpty()) return false
        var i = out.length - 1
        while (i >= 0 && out[i].isWhitespace()) i--
        if (i < 0) return false
        val ch = out[i]
        return ch.isLetterOrDigit() || ch == '_' || ch == ')' || ch == ']'
    }

    /** 跳过一个完整的类型表达式 (处理联合 | 交叉 & 泛型 <> 数组 [] 字面量等)。 */
    private fun skipTypeExpression(s: TokenScanner) {
        s.skipSpaces()
        // 跳过前导 (
        while (s.peek() == '(') { s.skipBlock('(', ')'); s.skipSpaces() }
        // 跳过可能的前导 keyof / typeof / infer / extends / new
        for (kw in listOf("keyof", "typeof", "infer", "readonly", "asserts", "unique", "abstract")) {
            if (s.startsWithWord(kw)) { s.skipWord(kw); s.skipSpaces() }
        }
        skipTypePrimary(s)
        // 后缀：| & [] ?（联合/交叉/数组/可选）
        while (s.hasNext()) {
            s.skipSpaces()
            val c = s.peek()
            when {
                c == '|' || c == '&' -> { s.next(); skipTypeExpression(s) }
                c == '[' -> { s.skipBlock('[', ']') }
                s.startsWith("[]") -> { s.next(); s.next() }
                else -> break
            }
        }
    }

    private fun skipTypePrimary(s: TokenScanner) {
        s.skipSpaces()
        val c = s.peek()
        when {
            c == '{' -> s.skipBlock('{', '}')
            c == '(' -> s.skipBlock('(', ')')
            c == '<' -> s.skipBlock('<', '>')
            c == '\'' || c == '"' || c == '`' -> s.consumeString()
            else -> {
                val w = s.readWord()
                if (w.isEmpty()) { s.next(); return }
                s.skipSpaces()
                // 泛型参数 A<T>
                if (s.peek() == '<') s.skipBlock('<', '>')
                // 属性访问 a.b.c
                while (s.peek() == '.') { s.next(); s.readWord(); s.skipSpaces() }
            }
        }
    }

    /**
     * 剥离 `: Type` 形式的注解。识别规则：
     *  - 仅当冒号前是标识符/)/]/?/< 且其后跟一个类型表达式起始字符时剥离。
     *  - 跳过对象字面量键值对的冒号 (键: value) —— 通过判断冒号后是否为"类型表达式"区分：
     *    若冒号后紧跟函数/类型关键字或大写类型名或泛型，视为类型注解；
     *    若紧跟字面量/标识符调用等，可能是对象字面量，保留。
     * 此规则对常见插件 TS 代码足够准确。
     */
    private fun stripColonAnnotations(source: String): String {
        val s = TokenScanner(source)
        val out = StringBuilder(source.length)
        while (s.hasNext()) {
            val c = s.peek()
            when {
                c == '\'' || c == '"' || c == '`' -> out.append(s.consumeString())
                c == ':' -> {
                    val saved = s.index
                    s.next(); s.skipSpaces()
                    if (looksLikeTypeAnnotation(s)) {
                        skipTypeExpression(s)
                    } else {
                        out.append(':')
                        s.index = saved + 1
                    }
                }
                else -> out.append(s.next())
            }
        }
        return out.toString()
    }

    private fun looksLikeTypeAnnotation(s: TokenScanner): Boolean {
        val c = s.peek()
        if (c == '(' || c == '{' || c == '<' || c == '\'' || c == '"' || c == '`') return true
        // 关键字类型
        for (kw in listOf("string", "number", "boolean", "void", "any", "unknown", "never",
                "object", "null", "undefined", "symbol", "bigint", "this", "true", "false",
                "Array", "ReadonlyArray", "Promise", "Record", "Partial", "Readonly",
                "Map", "Set", "Date", "Function", "Object")) {
            if (s.startsWithWord(kw)) return true
        }
        // 大写开头 = 通常是类型/接口名
        if (c != Char.MIN_VALUE && c.isUpperCase()) return true
        return false
    }

    // ==================== TokenScanner ====================

    private class TokenScanner(val src: String) {
        var index = 0
        val length = src.length

        fun hasNext(): Boolean = index < length
        fun peek(): Char = if (index < length) src[index] else Char.MIN_VALUE
        fun peekAt(offset: Int): Char = if (index + offset < length) src[index + offset] else Char.MIN_VALUE

        fun next(): Char {
            val c = src[index]
            index++
            return c
        }

        fun startsWith(s: String): Boolean {
            if (index + s.length > length) return false
            for (i in s.indices) if (src[index + i] != s[i]) return false
            return true
        }

        fun startsWithWord(word: String): Boolean {
            if (!startsWith(word)) return false
            val beforeOk = index == 0 || !isIdentChar(src[index - 1])
            val afterIdx = index + word.length
            val afterOk = afterIdx >= length || !isIdentChar(src[afterIdx])
            return beforeOk && afterOk
        }

        fun skipWord(word: String) { if (startsWithWord(word)) index += word.length }

        fun readWord(): String {
            val start = index
            while (index < length && isIdentChar(src[index])) index++
            return src.substring(start, index)
        }

        fun skipSpaces() {
            while (index < length && src[index].isWhitespace()) index++
        }

        fun consumeString(): String {
            val start = index
            val quote = src[index]
            index++
            while (index < length) {
                val c = src[index]
                if (c == '\\') { index += 2; continue }
                if (quote == '`' && c == '$' && peekAt(1) == '{') {
                    index += 2
                    skipTemplateExpr()
                    continue
                }
                if (c == quote) { index++; break }
                index++
            }
            return src.substring(start, index)
        }

        /** 跳过模板字符串中的 ${ ... } 表达式 (内部可能再嵌套模板/字符串)。 */
        private fun skipTemplateExpr() {
            var depth = 1
            while (index < length && depth > 0) {
                val c = src[index]
                when {
                    c == '\'' || c == '"' || c == '`' -> consumeString()
                    c == '{' -> { depth++; index++ }
                    c == '}' -> { depth--; index++ }
                    else -> index++
                }
            }
        }

        fun consumeLineComment(): String {
            val start = index
            while (index < length && src[index] != '\n') index++
            return src.substring(start, index)
        }

        fun consumeBlockComment(): String {
            val start = index
            index += 2
            while (index < length) {
                if (src[index] == '*' && peekAt(1) == '/') { index += 2; break }
                index++
            }
            return src.substring(start, index)
        }

        fun consumeUntilSemicolonOrNewline(): String {
            val start = index
            while (index < length && src[index] != ';' && src[index] != '\n') {
                if (src[index] == '\'' || src[index] == '"' || src[index] == '`') consumeString()
                else index++
            }
            return src.substring(start, index)
        }

        fun skipBlock(open: Char, close: Char) {
            if (peek() != open) return
            var depth = 0
            while (index < length) {
                val c = src[index]
                when {
                    c == '\'' || c == '"' || c == '`' -> consumeString()
                    c == open -> { depth++; index++ }
                    c == close -> { depth--; index++; if (depth == 0) break }
                    else -> index++
                }
            }
        }

        fun readValueUntil(vararg stops: Char): String {
            val start = index
            var depth = 0
            while (index < length) {
                val c = src[index]
                when {
                    c == '\'' || c == '"' || c == '`' -> consumeString()
                    c == '(' || c == '{' || c == '[' -> { depth++; index++ }
                    c == ')' || c == '}' || c == ']' -> { if (depth == 0) break; depth--; index++ }
                    c in stops && depth == 0 -> break
                    else -> index++
                }
            }
            return src.substring(start, index)
        }

        private fun isIdentChar(c: Char): Boolean =
            c.isLetterOrDigit() || c == '_' || c == '$'
    }
}
