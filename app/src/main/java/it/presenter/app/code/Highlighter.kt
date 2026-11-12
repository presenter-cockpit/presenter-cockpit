package it.presenter.app.code

/**
 * Lightweight code highlighting with no external grammars: comments, strings, numbers, keywords,
 * types and preprocessor directives, for the IDE languages (C, C++, Solidity, Python, shell, Makefile,
 * CMake, Java, Kotlin, JavaScript, TypeScript, Markdown, JSON, YAML). Enough to read and present
 * code; it is not a parser.
 */
object Highlighter {
    enum class Kind { COMMENT, STRING, NUMBER, KEYWORD, TYPE, PREPROCESSOR, HEADING }

    class Span(val start: Int, val end: Int, val kind: Kind)

    class Language(
        val name: String,
        val keywords: Set<String>,
        val types: Set<String> = emptySet(),
        val lineComment: List<String> = listOf("//"),
        val blockComment: Pair<String, String>? = "/*" to "*/",
        val preprocessor: Boolean = false,
        val markdown: Boolean = false,
    )

    private val C_KEYWORDS = setOf(
        "auto", "break", "case", "const", "continue", "default", "do", "else", "enum", "extern", "for", "goto", "if",
        "inline", "register", "restrict", "return", "sizeof", "static", "struct", "switch", "typedef", "union",
        "volatile", "while", "asm", "__asm__", "__volatile__", "true", "false", "NULL",
    )
    private val C_TYPES = setOf(
        "void", "char", "short", "int", "long", "float", "double", "signed", "unsigned", "bool", "_Bool",
        "size_t", "ssize_t", "uint8_t", "uint16_t", "uint32_t", "uint64_t", "int8_t", "int16_t", "int32_t", "int64_t",
        "FILE", "mpz_t", "mpq_t", "mpf_t",
    )
    private val CPP_KEYWORDS = C_KEYWORDS + setOf(
        "class", "namespace", "using", "template", "typename", "public", "private", "protected", "virtual", "override",
        "new", "delete", "this", "nullptr", "try", "catch", "throw", "constexpr", "auto", "operator", "friend",
        "explicit", "noexcept", "static_cast", "dynamic_cast", "reinterpret_cast", "const_cast",
    )

    val C = Language("C", C_KEYWORDS, C_TYPES, preprocessor = true)
    val CPP = Language("C++", CPP_KEYWORDS, C_TYPES + setOf("string", "vector", "map", "std"), preprocessor = true)
    val SOLIDITY = Language(
        "Solidity",
        setOf(
            "pragma", "solidity", "contract", "interface", "library", "function", "modifier", "event", "emit", "struct",
            "enum", "mapping", "public", "private", "internal", "external", "view", "pure", "payable", "returns", "return",
            "if", "else", "for", "while", "do", "break", "continue", "require", "revert", "assert", "memory", "storage",
            "calldata", "constructor", "is", "import", "using", "override", "virtual", "abstract", "immutable",
            "constant", "true", "false", "new", "delete", "msg", "this", "unchecked", "error", "try", "catch",
        ),
        setOf("address", "bool", "string", "bytes", "uint", "int", "uint256", "int256", "uint8", "bytes32", "uint128", "uint64"),
    )
    val PYTHON = Language(
        "Python",
        setOf(
            "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else", "except",
            "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass",
            "raise", "return", "try", "while", "with", "yield", "True", "False", "None", "self",
        ),
        setOf("int", "float", "str", "bytes", "list", "dict", "set", "tuple", "bool"),
        lineComment = listOf("#"), blockComment = null,
    )
    val SHELL = Language(
        "Shell",
        setOf("if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac", "in", "function", "return", "export", "local", "echo", "exit", "set", "unset", "source"),
        lineComment = listOf("#"), blockComment = null,
    )
    val MAKE = Language("Makefile", setOf("ifeq", "ifneq", "ifdef", "ifndef", "else", "endif", "include", "define", "endef", "export", ".PHONY"), lineComment = listOf("#"), blockComment = null)
    val CMAKE = Language(
        "CMake",
        setOf("cmake_minimum_required", "project", "add_executable", "add_library", "target_link_libraries", "target_include_directories", "set", "if", "else", "endif", "find_package", "include_directories", "add_subdirectory", "option", "message"),
        lineComment = listOf("#"), blockComment = null,
    )
    val JAVA = Language(
        "Java",
        setOf("abstract", "assert", "break", "case", "catch", "class", "continue", "default", "do", "else", "enum", "extends", "final", "finally", "for", "if", "implements", "import", "instanceof", "interface", "native", "new", "package", "private", "protected", "public", "return", "static", "super", "switch", "synchronized", "this", "throw", "throws", "try", "var", "void", "volatile", "while", "true", "false", "null", "record"),
        setOf("int", "long", "short", "byte", "char", "boolean", "float", "double", "String", "Object"),
    )
    val KOTLIN = Language(
        "Kotlin",
        setOf("as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "val", "var", "when", "while", "import", "private", "public", "internal", "protected", "override", "open", "data", "sealed", "companion", "const", "lateinit", "suspend", "by"),
        setOf("Int", "Long", "Float", "Double", "Boolean", "String", "Unit", "Any", "List", "Map", "Set"),
    )
    val JS = Language(
        "JavaScript",
        setOf("break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else", "export", "extends", "finally", "for", "function", "if", "import", "in", "instanceof", "let", "new", "return", "super", "switch", "this", "throw", "try", "typeof", "var", "void", "while", "with", "yield", "async", "await", "true", "false", "null", "undefined", "of", "from"),
        setOf("number", "string", "boolean", "any", "unknown", "never", "interface", "type", "enum", "implements", "readonly", "private", "public", "protected"),
    )
    val JSON = Language("JSON", setOf("true", "false", "null"), lineComment = emptyList(), blockComment = null)
    val YAML = Language("YAML", setOf("true", "false", "null", "yes", "no"), lineComment = listOf("#"), blockComment = null)
    val MARKDOWN = Language("Markdown", emptySet(), lineComment = emptyList(), blockComment = null, markdown = true)
    val PLAIN = Language("Text", emptySet(), lineComment = emptyList(), blockComment = null)

    /** Language from the file name. */
    fun forFile(name: String): Language {
        val lower = name.lowercase()
        return when {
            lower == "makefile" || lower.endsWith(".mk") -> MAKE
            lower == "cmakelists.txt" || lower.endsWith(".cmake") -> CMAKE
            else -> when (lower.substringAfterLast('.', "")) {
                "c", "h" -> C
                "cpp", "cc", "cxx", "hpp", "hh", "hxx" -> CPP
                "sol" -> SOLIDITY
                "py" -> PYTHON
                "sh", "bash", "zsh" -> SHELL
                "java" -> JAVA
                "kt", "kts" -> KOTLIN
                "js", "mjs", "cjs", "ts", "tsx", "jsx" -> JS
                "json" -> JSON
                "yml", "yaml" -> YAML
                "md", "markdown" -> MARKDOWN
                else -> PLAIN
            }
        }
    }

    /** Colored spans of [text] (in order, not overlapping). */
    fun highlight(text: String, lang: Language): List<Span> {
        val out = ArrayList<Span>()
        if (lang.markdown) return markdown(text)
        var i = 0
        val n = text.length
        var lineStart = true
        while (i < n) {
            val c = text[i]
            if (c == '\n') {
                lineStart = true
                i++
                continue
            }
            // Preprocessor directives (#include, #define…): the whole line.
            if (lang.preprocessor && lineStart && c == '#') {
                val end = text.indexOf('\n', i).let { if (it < 0) n else it }
                out.add(Span(i, end, Kind.PREPROCESSOR))
                i = end
                continue
            }
            if (!c.isWhitespace()) lineStart = false
            val line = lang.lineComment.firstOrNull { text.startsWith(it, i) }
            if (line != null) {
                val end = text.indexOf('\n', i).let { if (it < 0) n else it }
                out.add(Span(i, end, Kind.COMMENT))
                i = end
                continue
            }
            val block = lang.blockComment
            if (block != null && text.startsWith(block.first, i)) {
                val close = text.indexOf(block.second, i + block.first.length)
                val end = if (close < 0) n else close + block.second.length
                out.add(Span(i, end, Kind.COMMENT))
                i = end
                continue
            }
            if (c == '"' || c == '\'' || (c == '`' && lang == JS)) {
                var j = i + 1
                while (j < n && text[j] != c && text[j] != '\n') j += if (text[j] == '\\') 2 else 1
                val end = (j + 1).coerceAtMost(n)
                out.add(Span(i, end, Kind.STRING))
                i = end
                continue
            }
            if (c.isDigit() && (i == 0 || !isWordChar(text[i - 1]))) {
                var j = i
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '.' || text[j] == '_')) j++
                out.add(Span(i, j, Kind.NUMBER))
                i = j
                continue
            }
            if (isWordStart(c)) {
                // The first character already belongs to the word (also the '.' of ".PHONY"): always advance.
                var j = i + 1
                while (j < n && isWordChar(text[j])) j++
                val word = text.substring(i, j)
                when (word) {
                    in lang.keywords -> out.add(Span(i, j, Kind.KEYWORD))
                    in lang.types -> out.add(Span(i, j, Kind.TYPE))
                }
                i = j
                continue
            }
            i++
        }
        return out
    }

    private fun markdown(text: String): List<Span> {
        val out = ArrayList<Span>()
        var start = 0
        var fenced = false
        while (start <= text.length) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            val line = text.substring(start, end)
            when {
                line.trimStart().startsWith("```") -> {
                    fenced = !fenced
                    out.add(Span(start, end, Kind.PREPROCESSOR))
                }
                fenced -> out.add(Span(start, end, Kind.STRING))
                line.startsWith("#") -> out.add(Span(start, end, Kind.HEADING))
            }
            start = end + 1
        }
        return out
    }

    private fun isWordStart(c: Char) = c.isLetter() || c == '_' || c == '.' || c == '$'
    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
}
