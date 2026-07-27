package com.ravenemu.ci

/**
 * Évaluateur du sous-ensemble d'expressions GitHub Actions employé par les
 * conditions `if:` de ce dépôt : littéraux, chemins de contexte, `==`, `!=`,
 * `!`, `&&`, `||`, parenthèses et `startsWith(...)`.
 *
 * Le but n'est pas de réimplémenter GitHub Actions mais de pouvoir **exécuter**
 * les conditions de déclenchement dans un test : « ce job se lance-t-il sur un
 * push de branche ordinaire ? » est alors une question à laquelle on répond en
 * évaluant la condition réelle du workflow, pas en cherchant un motif dans du
 * texte. Toute construction hors de ce sous-ensemble lève une erreur : mieux
 * vaut un test qui refuse de conclure qu'un test qui approuve à tort.
 */
object ConditionEvaluator {

    /** Contexte d'un déclenchement : `github.event_name`, `github.ref`, etc. */
    data class Context(val values: Map<String, Any?>) {
        operator fun get(path: String): Any? = values[path]
    }

    fun evaluate(expression: String, context: Context): Boolean =
        truthy(Parser(tokenize(expression), context).parseExpression())

    // --- Analyse lexicale -------------------------------------------------

    private fun tokenize(source: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c.isWhitespace() -> i++
                c == '\'' -> {
                    val end = source.indexOf('\'', i + 1)
                    require(end > 0) { "Chaîne non terminée dans « $source »" }
                    tokens += source.substring(i, end + 1)
                    i = end + 1
                }
                source.startsWith("&&", i) || source.startsWith("||", i) ||
                    source.startsWith("==", i) || source.startsWith("!=", i) -> {
                    tokens += source.substring(i, i + 2)
                    i += 2
                }
                c == '(' || c == ')' || c == ',' || c == '!' -> {
                    tokens += c.toString()
                    i++
                }
                c.isLetterOrDigit() || c == '_' -> {
                    var end = i
                    while (end < source.length &&
                        (source[end].isLetterOrDigit() || source[end] == '_' || source[end] == '.')
                    ) {
                        end++
                    }
                    tokens += source.substring(i, end)
                    i = end
                }
                else -> error("Caractère inattendu « $c » dans « $source »")
            }
        }
        return tokens
    }

    // --- Analyse syntaxique et évaluation ---------------------------------

    private class Parser(private val tokens: List<String>, private val context: Context) {
        private var position = 0

        private fun peek(): String? = tokens.getOrNull(position)

        private fun consume(expected: String) {
            require(peek() == expected) { "« $expected » attendu, trouvé « ${peek()} »" }
            position++
        }

        fun parseExpression(): Any? = parseOr()

        private fun parseOr(): Any? {
            var left = parseAnd()
            while (peek() == "||") {
                position++
                val right = parseAnd()
                left = truthy(left) || truthy(right)
            }
            return left
        }

        private fun parseAnd(): Any? {
            var left = parseComparison()
            while (peek() == "&&") {
                position++
                val right = parseComparison()
                left = truthy(left) && truthy(right)
            }
            return left
        }

        private fun parseComparison(): Any? {
            val left = parseUnary()
            val operator = peek()
            if (operator != "==" && operator != "!=") return left
            position++
            val right = parseUnary()
            val equal = normalize(left) == normalize(right)
            return if (operator == "==") equal else !equal
        }

        /** `null` et chaîne vide sont interchangeables côté GitHub Actions. */
        private fun normalize(value: Any?): Any? = if (value == null) "" else value

        private fun parseUnary(): Any? {
            if (peek() == "!") {
                position++
                return !truthy(parseUnary())
            }
            return parsePrimary()
        }

        private fun parsePrimary(): Any? {
            val token = peek() ?: error("Expression incomplète")
            if (token == "(") {
                position++
                val value = parseExpression()
                consume(")")
                return value
            }
            if (token.startsWith("'")) {
                position++
                return token.substring(1, token.length - 1)
            }
            position++
            if (peek() == "(") {
                position++
                val arguments = mutableListOf<Any?>()
                if (peek() != ")") {
                    arguments += parseExpression()
                    while (peek() == ",") {
                        position++
                        arguments += parseExpression()
                    }
                }
                consume(")")
                return call(token, arguments)
            }
            return when (token) {
                "true" -> true
                "false" -> false
                else -> context[token]
            }
        }

        private fun call(name: String, arguments: List<Any?>): Any? = when (name) {
            "startsWith" -> {
                require(arguments.size == 2) { "startsWith attend deux arguments" }
                (arguments[0] as? String).orEmpty()
                    .startsWith((arguments[1] as? String).orEmpty())
            }
            "success" -> true
            "always" -> true
            else -> error("Fonction non prise en charge dans ce test : $name")
        }
    }
}

/** Véracité au sens de GitHub Actions : chaîne vide et `null` sont faux. */
private fun truthy(value: Any?): Boolean = when (value) {
    null -> false
    is Boolean -> value
    is String -> value.isNotEmpty()
    else -> error("Valeur non booléenne : $value")
}
