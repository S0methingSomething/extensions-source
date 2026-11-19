package keiyoushi.lib.selexpr

/**
 * A tiny, SEL-inspired rule language for scoring items.
 *
 * Rules are entered by the user as lines of text. Each line is an optional
 * `[`/* name`]`[​`*/` comment prefix, then an optional `[SCORE:]` prefix, then
 * an expression. `SCORE` defaults to `1` when omitted, and a line that is
 * only a comment is skipped. An item's score is the sum over all rules: an
 * expression that evaluates to a number contributes that number directly
 * (e.g. `official ? 100 : 10`); any other truthy result contributes the
 * rule's `SCORE:` prefix; falsy results contribute nothing. Named rules
 * identify the rule in validation errors.
 *
 * Supported syntax: `?:` ternary, `and`/`or`/`not` (plus `&&`/`||`/`!`),
 * `==` `!=` `<` `<=` `>` `>=`, `in` (list membership or substring),
 * `+ - * /`, unary `-`, parentheses, number/string/boolean literals and
 * `[array]` literals. Functions: `includes(haystack, needle, ...)`
 * (case-insensitive substring; true when the haystack contains any needle),
 * `matches(value, '/pattern/flags')` with a quoted literal pattern,
 * `min`, `max`, `abs`.
 *
 * Expressions are evaluated against a bindings map of variable name to value
 * (number, boolean, string, null or list). Identifiers are checked against
 * the known keys when the rules are compiled, so a typo is a compile error
 * rather than a silent runtime null.
 */
object SelExpr {

    /**
     * Parses and validates `source` against `knownVariables`. Throws [Exception]
     * with a user-readable message (line number, rule name and what is wrong)
     * when the source cannot be used.
     */
    fun compile(source: String, knownVariables: Set<String>): Rules {
        val rules = mutableListOf<Rule>()
        source.lineSequence()
            .mapIndexed { index, line -> index + 1 to line }
            .filter { (_, line) -> line.isNotBlank() }
            .forEach { (lineNumber, line) ->
                val (name, rest) = splitName(line)
                val rule = parseRule(rest, name, lineNumber, knownVariables)
                if (rule != null) rules.add(rule)
            }
        if (rules.isEmpty()) throw Exception("No rules found")
        return Rules(rules)
    }

    /**
     * Compiled rules. Compute an item's total score with [score], which runs
     * every rule's expression against the bindings and sums the contributions
     * of the ones that are truthy.
     */
    class Rules internal constructor(rules: List<Rule>) {
        private val rules: List<Rule> = rules.toList()
        private val allVariables: Set<String> = rules.flatMapTo(mutableSetOf()) { it.variables }

        /** Variable names referenced by any rule, for optional lazy setup. */
        val variables: Set<String> get() = allVariables

        /**
         * Sums the contributions of all rules. An expression that evaluates
         * to a number contributes that number directly (SEL-style, e.g.
         * `official ? 100 : 10`); any other truthy result contributes the
         * rule's `SCORE:` prefix (default 1).
         */
        fun score(bindings: Map<String, Any?>): Double {
            var total = 0.0
            for (rule in rules) {
                val result = rule.expression.eval(bindings)
                total += when {
                    result is Number -> result.toDouble()
                    result.isTruthy -> rule.score
                    else -> 0.0
                }
            }
            return total
        }
    }

    internal class Rule(
        val name: String?,
        val score: Double,
        val expression: Node,
    ) {
        val variables = expression.variables
    }

    // =============================== Parsing ===============================

    private fun splitName(line: String): Pair<String?, String> {
        if (!line.startsWith("/*")) return null to line
        val end = line.indexOf("*/")
        if (end < 0) throw Exception("Unclosed /* comment")
        val name = line.substring(2, end).trim().removePrefix("#").trim()
        return (name.takeIf { it.isNotEmpty() }) to line.substring(end + 2)
    }

    private fun parseRule(
        text: String,
        name: String?,
        lineNumber: Int,
        knownVariables: Set<String>,
    ): Rule? {
        val tokens = Lexer(text, lineNumber, name).lex()
        if (tokens.isEmpty()) return null // comment-only line

        // optional leading score, `-10000: expr` or `100: expr`
        var index = 0
        var score = 1.0
        if (hasScoreSeparator(tokens)) {
            val negative = tokens[0] is Token.Op && (tokens[0] as Token.Op).symbol == "-"
            val value = when {
                negative -> {
                    val number = tokens.getOrNull(1) as? Token.Number
                        ?: throw Exception(where(lineNumber, name, "Expected number after '-'"))
                    -number.value
                }
                else -> (tokens[0] as? Token.Number)?.value
                    ?: throw Exception(where(lineNumber, name, "Expected a number before ':'"))
            }
            index = if (negative) 2 else 1
            val separator = tokens.getOrNull(index) as? Token.Op
                ?: throw Exception(where(lineNumber, name, "Expected ':' after score"))
            if (separator.symbol != ":") throw Exception(where(lineNumber, name, "Expected ':' after score"))
            index++
            score = value
        }

        val parser = Parser(tokens, index, lineNumber, name, knownVariables)
        val node = parser.parseExpression()
        parser.expectEnd()
        return Rule(name, score, node)
    }

    private fun hasScoreSeparator(tokens: List<Token>): Boolean {
        if (tokens.isEmpty()) return false
        val i = if (tokens[0] is Token.Op && (tokens[0] as Token.Op).symbol == "-") 2 else 1
        return (tokens.getOrNull(i) as? Token.Op)?.symbol == ":"
    }

    private fun where(lineNumber: Int, name: String?, message: String): String {
        val at = if (name != null) "line $lineNumber ($name): " else "line $lineNumber: "
        return at + message
    }

    // ================================ Lexer ================================

    private sealed class Token {
        class Number(val value: Double) : Token()
        class Str(val value: String) : Token()
        class Ident(val name: String) : Token()
        class Op(val symbol: String) : Token()
    }

    private class Lexer(private val text: String, private val lineNumber: Int, private val ruleName: String?) {
        fun lex(): List<Token> {
            val tokens = mutableListOf<Token>()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c.isWhitespace() -> i++
                    text.startsWith("//", i) -> return tokens
                    text.startsWith("/*", i) -> throw Exception(where(lineNumber, ruleName, "Nested /* comment not allowed"))
                    c.isDigit() -> {
                        var j = i
                        while (j < text.length && (text[j].isDigit() || text[j] == '.')) j++
                        tokens.add(Token.Number(text.substring(i, j).toDouble()))
                        i = j
                    }
                    c == '\'' || c == '"' -> {
                        val quote = c
                        val sb = StringBuilder()
                        var j = i + 1
                        while (j < text.length && text[j] != quote) {
                            if (text[j] == '\\' && j + 1 < text.length) {
                                sb.append(
                                    when (text[j + 1]) {
                                        'n' -> '\n'
                                        't' -> '\t'
                                        else -> text[j + 1]
                                    },
                                )
                                j += 2
                            } else {
                                sb.append(text[j])
                                j++
                            }
                        }
                        if (j >= text.length) throw Exception(where(lineNumber, ruleName, "Unclosed string literal"))
                        tokens.add(Token.Str(sb.toString()))
                        i = j + 1
                    }
                    c.isLetter() || c == '_' -> {
                        var j = i
                        while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                        tokens.add(Token.Ident(text.substring(i, j)))
                        i = j
                    }
                    else -> {
                        val two = text.substring(i, minOf(i + 2, text.length))
                        val op = when (two) {
                            "==", "!=", "<=", ">=", "&&", "||" -> two
                            else -> c.toString()
                        }
                        if (op.length == 1 && op[0] !in "()<>+-*/![],?:") {
                            throw Exception(where(lineNumber, ruleName, "Unexpected character '$op'"))
                        }
                        tokens.add(Token.Op(op))
                        i += op.length
                    }
                }
            }
            return tokens
        }
    }

    // ================================ Parser ===============================

    // Precedence (loosest to tightest): ?: < or < and < not < comparison/in < + - < * / < unary
    private class Parser(
        private val tokens: List<Token>,
        private var index: Int,
        private val lineNumber: Int,
        private val ruleName: String?,
        private val knownVariables: Set<String>,
    ) {
        fun parseExpression(): Node = parseTernary()

        fun expectEnd() {
            if (index < tokens.size) throw Exception(where(lineNumber, ruleName, "Unexpected trailing tokens"))
        }

        private fun parseTernary(): Node {
            val condition = parseOr()
            if (matchOp("?")) {
                val thenBranch = parseTernary()
                expectOp(":")
                val elseBranch = parseTernary()
                return Ternary(condition, thenBranch, elseBranch)
            }
            return condition
        }

        private fun parseOr(): Node {
            var left = parseAnd()
            while (true) {
                when {
                    matchKeyword("or") -> left = Or(left, parseAnd())
                    matchOp("||") -> left = Or(left, parseAnd())
                    else -> return left
                }
            }
        }

        private fun parseAnd(): Node {
            var left = parseNot()
            while (true) {
                when {
                    matchKeyword("and") -> left = And(left, parseNot())
                    matchOp("&&") -> left = And(left, parseNot())
                    else -> return left
                }
            }
        }

        private fun parseNot(): Node {
            if (matchKeyword("not") || matchOp("!")) return Not(parseNot())
            return parseComparison()
        }

        private fun parseComparison(): Node {
            val left = parseAdditive()
            if (matchKeyword("in")) return In(left, parseAdditive())
            val op = when {
                matchOp("==") -> "=="
                matchOp("!=") -> "!="
                matchOp("<=") -> "<="
                matchOp(">=") -> ">="
                matchOp("<") -> "<"
                matchOp(">") -> ">"
                else -> return left
            }
            return Comparison(op, left, parseAdditive())
        }

        private fun parseAdditive(): Node {
            var left = parseMultiplicative()
            while (true) {
                when {
                    matchOp("+") -> left = Arithmetic("+", left, parseMultiplicative())
                    matchOp("-") -> left = Arithmetic("-", left, parseMultiplicative())
                    else -> return left
                }
            }
        }

        private fun parseMultiplicative(): Node {
            var left = parseUnary()
            while (true) {
                when {
                    matchOp("*") -> left = Arithmetic("*", left, parseUnary())
                    matchOp("/") -> left = Arithmetic("/", left, parseUnary())
                    else -> return left
                }
            }
        }

        private fun parseUnary(): Node {
            if (matchOp("-")) return Negate(parseUnary())
            return parsePrimary()
        }

        private fun parsePrimary(): Node {
            val token = peek() ?: throw Exception(where(lineNumber, ruleName, "Unexpected end of expression"))
            return when (token) {
                is Token.Number -> {
                    next()
                    Literal(token.value)
                }
                is Token.Str -> {
                    next()
                    Literal(token.value)
                }
                is Token.Ident -> {
                    next()
                    if (matchOp("(")) parseCall(token.name) else variable(token.name)
                }
                is Token.Op -> when (token.symbol) {
                    "(" -> {
                        next()
                        val node = parseExpression()
                        expectOp(")")
                        node
                    }
                    "[" -> {
                        next()
                        val items = mutableListOf<Node>()
                        if (!matchOp("]")) {
                            while (true) {
                                items.add(parseExpression())
                                if (matchOp("]")) break
                                expectOp(",")
                            }
                        }
                        ArrayLiteral(items)
                    }
                    else -> throw Exception(where(lineNumber, ruleName, "Unexpected token '${token.symbol}'"))
                }
            }
        }

        private fun parseCall(name: String): Node {
            val args = mutableListOf<Node>()
            if (!matchOp(")")) {
                while (true) {
                    args.add(parseExpression())
                    if (matchOp(")")) break
                    expectOp(",")
                }
            }
            return when (name) {
                "includes" -> {
                    if (args.size < 2) throw Exception(where(lineNumber, ruleName, "includes() needs a haystack and at least one needle"))
                    Includes(args[0], args.drop(1))
                }
                "matches" -> {
                    if (args.size != 2) throw Exception(where(lineNumber, ruleName, "matches() needs a value and a '/pattern/flags' string"))
                    Matches(args[1], args[0], lineNumber, ruleName)
                }
                "min" -> {
                    if (args.isEmpty()) throw Exception(where(lineNumber, ruleName, "min() needs at least one argument"))
                    Min(args)
                }
                "max" -> {
                    if (args.isEmpty()) throw Exception(where(lineNumber, ruleName, "max() needs at least one argument"))
                    Max(args)
                }
                "abs" -> {
                    if (args.size != 1) throw Exception(where(lineNumber, ruleName, "abs() needs exactly one argument"))
                    Abs(args[0])
                }
                else -> throw Exception(where(lineNumber, ruleName, "Unknown function '$name'"))
            }
        }

        private fun variable(name: String): Node {
            when (name) {
                "true" -> return Literal(true)
                "false" -> return Literal(false)
            }
            if (name !in knownVariables) {
                throw Exception(where(lineNumber, ruleName, "Unknown variable '$name'"))
            }
            return Variable(name)
        }

        private fun peek(): Token? = tokens.getOrNull(index)

        private fun next(): Token? = tokens.getOrNull(index)?.also { index++ }

        private fun matchOp(symbol: String): Boolean {
            val token = tokens.getOrNull(index)
            if (token is Token.Op && token.symbol == symbol) {
                index++
                return true
            }
            return false
        }

        private fun matchKeyword(word: String): Boolean {
            val token = tokens.getOrNull(index)
            if (token is Token.Ident && token.name == word) {
                index++
                return true
            }
            return false
        }

        private fun expectOp(symbol: String) {
            if (!matchOp(symbol)) throw Exception(where(lineNumber, ruleName, "Expected '$symbol'"))
        }
    }

    // ================================ AST ==================================

    internal sealed interface Node {
        fun eval(bindings: Map<String, Any?>): Any?
        val variables: Set<String>
    }
    private class Literal(val value: Any?) : Node {
        override fun eval(bindings: Map<String, Any?>) = value
        override val variables get() = emptySet<String>()
    }

    private class Variable(val name: String) : Node {
        override fun eval(bindings: Map<String, Any?>): Any? {
            if (!bindings.containsKey(name)) throw Exception("No value provided for '$name'")
            return bindings[name]
        }
        override val variables get() = setOf(name)
    }

    private class Ternary(val condition: Node, val thenBranch: Node, val elseBranch: Node) : Node {
        override fun eval(bindings: Map<String, Any?>) = if (condition.eval(bindings).isTruthy) thenBranch.eval(bindings) else elseBranch.eval(bindings)
        override val variables get() = condition.variables + thenBranch.variables + elseBranch.variables
    }

    private class Or(val left: Node, val right: Node) : Node {
        override fun eval(bindings: Map<String, Any?>) = left.eval(bindings).isTruthy || right.eval(bindings).isTruthy
        override val variables get() = left.variables + right.variables
    }

    private class And(val left: Node, val right: Node) : Node {
        override fun eval(bindings: Map<String, Any?>) = left.eval(bindings).isTruthy && right.eval(bindings).isTruthy
        override val variables get() = left.variables + right.variables
    }

    private class Not(val inner: Node) : Node {
        override fun eval(bindings: Map<String, Any?>) = !inner.eval(bindings).isTruthy
        override val variables get() = inner.variables
    }

    private class In(val item: Node, val list: Node) : Node {
        override fun eval(bindings: Map<String, Any?>): Any {
            val value = item.eval(bindings)
            return when (val container = list.eval(bindings)) {
                is List<*> -> container.contains(value)
                is String -> value is String && container.contains(value)
                else -> throw Exception("'in' needs a list")
            }
        }
        override val variables get() = item.variables + list.variables
    }

    private class Comparison(val op: String, val left: Node, val right: Node) : Node {
        override fun eval(bindings: Map<String, Any?>): Any {
            val a = left.eval(bindings)
            val b = right.eval(bindings)
            return when (op) {
                "==" -> valuesEqual(a, b)
                "!=" -> !valuesEqual(a, b)
                else -> {
                    val x = a.asNumber
                    val y = b.asNumber
                    when (op) {
                        "<" -> x < y
                        "<=" -> x <= y
                        ">" -> x > y
                        ">=" -> x >= y
                        else -> throw Exception("Unknown comparison '$op'")
                    }
                }
            }
        }
        override val variables get() = left.variables + right.variables
    }

    private class Arithmetic(val op: String, val left: Node, val right: Node) : Node {
        override fun eval(bindings: Map<String, Any?>): Any {
            val a = left.eval(bindings)
            if (op == "+" && (a is String || right.eval(bindings) is String)) {
                return a.toString() + right.eval(bindings).toString()
            }
            val x = a.asNumber
            val y = right.eval(bindings).asNumber
            return when (op) {
                "+" -> x + y
                "-" -> x - y
                "*" -> x * y
                "/" -> if (y == 0.0) 0.0 else x / y
                else -> throw Exception("Unknown operator '$op'")
            }
        }
        override val variables get() = left.variables + right.variables
    }

    private class Negate(val inner: Node) : Node {
        override fun eval(bindings: Map<String, Any?>) = -inner.eval(bindings).asNumber
        override val variables get() = inner.variables
    }

    private class ArrayLiteral(val items: List<Node>) : Node {
        override fun eval(bindings: Map<String, Any?>) = items.map { it.eval(bindings) }
        override val variables get() = items.flatMapTo(mutableSetOf()) { it.variables }
    }

    private class Includes(val haystack: Node, val needles: List<Node>) : Node {
        override fun eval(bindings: Map<String, Any?>): Any {
            val value = haystack.eval(bindings)?.toString()?.lowercase() ?: return false
            return needles.any { needle -> value.contains(needle.eval(bindings).toString().lowercase()) }
        }
        override val variables get() = haystack.variables + needles.flatMapTo(mutableSetOf()) { it.variables }
    }

    private class Matches(pattern: Node, val value: Node, lineNumber: Int, ruleName: String?) : Node {
        // SEL requires a quoted literal pattern; compiling here validates it on save
        private val regex: Regex = try {
            pattern.eval(emptyMap()).toString().toRegexOrThrow()
        } catch (e: Exception) {
            throw Exception(where(lineNumber, ruleName, e.message ?: "Invalid matches() pattern"))
        }

        override fun eval(bindings: Map<String, Any?>): Any {
            val target = value.eval(bindings)?.toString() ?: return false
            return regex.containsMatchIn(target)
        }
        override val variables get() = value.variables
    }

    private class Min(val args: List<Node>) : Node {
        override fun eval(bindings: Map<String, Any?>) = args.minOf { it.eval(bindings).asNumber }
        override val variables get() = args.flatMapTo(mutableSetOf()) { it.variables }
    }

    private class Max(val args: List<Node>) : Node {
        override fun eval(bindings: Map<String, Any?>) = args.maxOf { it.eval(bindings).asNumber }
        override val variables get() = args.flatMapTo(mutableSetOf()) { it.variables }
    }

    private class Abs(val arg: Node) : Node {
        override fun eval(bindings: Map<String, Any?>) = kotlin.math.abs(arg.eval(bindings).asNumber)
        override val variables get() = arg.variables
    }

    // ============================== Values =================================

    private val Any?.isTruthy: Boolean
        get() = when (this) {
            null -> false
            is Boolean -> this
            is Number -> toDouble() != 0.0
            is String -> isNotEmpty()
            is List<*> -> isNotEmpty()
            else -> true
        }

    private val Any?.asNumber: Double
        get() = when (this) {
            is Double -> this
            is Int -> toDouble()
            is Long -> toDouble()
            is Float -> toDouble()
            is Boolean -> if (this) 1.0 else 0.0
            is String -> toDoubleOrNull() ?: throw Exception("'$this' is not a number")
            else -> throw Exception("'$this' is not a number")
        }

    private fun valuesEqual(a: Any?, b: Any?): Boolean = when {
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        a is Boolean && b is Boolean -> a == b
        else -> a == b
    }

    private fun String.toRegexOrThrow(): Regex {
        val trimmed = trim()
        val lastSlash = trimmed.lastIndexOf('/')
        val (pattern, flags) = if (trimmed.startsWith("/") && lastSlash > 0) {
            trimmed.substring(1, lastSlash) to trimmed.substring(lastSlash + 1)
        } else {
            trimmed to ""
        }
        if (flags.any { it !in "ismx" }) throw Exception("Invalid regex flags '$flags'")
        val options = buildSet {
            if ('i' in flags) add(RegexOption.IGNORE_CASE)
            if ('s' in flags) add(RegexOption.DOT_MATCHES_ALL)
            if ('m' in flags) add(RegexOption.MULTILINE)
            if ('x' in flags) add(RegexOption.COMMENTS)
        }
        return try {
            Regex(pattern, options)
        } catch (e: Exception) {
            throw Exception("Invalid regex '$trimmed'")
        }
    }
}
