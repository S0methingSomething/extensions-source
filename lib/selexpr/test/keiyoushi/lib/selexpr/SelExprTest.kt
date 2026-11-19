package keiyoushi.lib.selexpr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SelExprTest {

    private val vars = setOf(
        "group",
        "title",
        "genres",
        "partial",
        "pages",
        "number",
        "official",
        "mangadex",
        "year",
        "slug",
    )

    private fun score(source: String, bindings: Map<String, Any?>): Double = SelExpr.compile(source, vars).score(bindings)

    @Test
    fun `simple scoring with default score`() {
        val rules = SelExpr.compile("includes(group, 'Asura')", vars)
        assertEquals(1.0, rules.score(mapOf("group" to "Asura Scans")), 1e-9)
        assertEquals(0.0, rules.score(mapOf("group" to "Mangadex")), 1e-9)
    }

    @Test
    fun `negative score as hard exclude`() {
        val rules = SelExpr.compile("-10000: partial", vars)
        assertEquals(-10000.0, rules.score(mapOf("partial" to true)), 1e-9)
        // false binding is falsy
        assertEquals(0.0, rules.score(mapOf("partial" to false)), 1e-9)
    }

    @Test
    fun `weighted sum of multiple rules`() {
        val source = """
            /* fan-TL */ 500: includes(group, 'Princess Alliance', 'Bored Corona Kids')
            /* official */ 10: official
            -10000: partial
        """.trimIndent()
        val rules = SelExpr.compile(source, vars)
        assertEquals(
            510.0,
            rules.score(
                mapOf("group" to "Princess Alliance", "official" to true, "partial" to false),
            ),
            1e-9,
        )
        // a partial upload from the fan-TL group: 500 + 10 - 10000
        assertEquals(-9490.0, rules.score(mapOf("group" to "Princess Alliance", "official" to true, "partial" to true)), 1e-9)
        assertEquals(10.0, rules.score(mapOf("group" to "Some Other Group", "official" to true, "partial" to false)), 1e-9)
    }

    @Test
    fun `ternary and arithmetic`() {
        val source = "official ? 100 : number > 100 ? 50 : 10"
        val rules = SelExpr.compile(source, vars)
        assertEquals(100.0, rules.score(mapOf("official" to true, "number" to 1)), 1e-9)
        assertEquals(50.0, rules.score(mapOf("official" to false, "number" to 150)), 1e-9)
        assertEquals(10.0, rules.score(mapOf("official" to false, "number" to 5)), 1e-9)
    }

    @Test
    fun `ternary chained comparison as score`() {
        val source = "not partial ? pages / 100 : -10000"
        val rules = SelExpr.compile(source, vars)
        assertEquals(2.0, rules.score(mapOf("partial" to false, "pages" to 200)), 1e-9)
        assertEquals(-10000.0, rules.score(mapOf("partial" to true, "pages" to 200)), 1e-9)
    }

    @Test
    fun `ternary and arithmetic expressions as scores`() {
        val source = "(pages / 100) * 5"
        val rules = SelExpr.compile(source, vars)
        // a number result contributes itself, not the SCORE: prefix
        assertEquals(10.0, rules.score(mapOf("pages" to 200)), 1e-9)
        assertEquals(0.0, rules.score(mapOf("pages" to 0)), 1e-9)
    }

    @Test
    fun `in operator with list binding and substring`() {
        val rules = SelExpr.compile("'Romance Fantasy' in genres", vars)
        assertEquals(1.0, rules.score(mapOf("genres" to listOf("Romance Fantasy", "Drama"))), 1e-9)
        assertEquals(0.0, rules.score(mapOf("genres" to listOf("Action"))), 1e-9)

        // `in` on strings is a case-sensitive substring check, like in SEL/JS;
        // includes() is the case-insensitive variant
        val substring = SelExpr.compile("'Fan' in title", vars)
        assertEquals(1.0, substring.score(mapOf("title" to "A Fansubber's Dream")), 1e-9)
        assertEquals(0.0, substring.score(mapOf("title" to "Official Copy")), 1e-9)
    }

    @Test
    fun `in operator with array literal`() {
        val rules = SelExpr.compile("group in ['Asura', 'Mangadex']", vars)
        assertEquals(1.0, rules.score(mapOf("group" to "Asura")), 1e-9)
        assertEquals(0.0, rules.score(mapOf("group" to "Flame")), 1e-9)
    }

    @Test
    fun `string comparison and concatenation`() {
        val concat = SelExpr.compile("group == 'asura' + ' scans'", vars)
        assertEquals(1.0, concat.score(mapOf("group" to "asura scans")), 1e-9)

        val ne = SelExpr.compile("group != 'official'", vars)
        assertEquals(1.0, ne.score(mapOf("group" to "fan")), 1e-9)
        assertEquals(0.0, ne.score(mapOf("group" to "official")), 1e-9)
    }

    @Test
    fun `case-insensitive includes with multiple needles`() {
        val rules = SelExpr.compile("includes(group, 'princess alliance', 'Bored Corona Kids')", vars)
        assertEquals(1.0, rules.score(mapOf("group" to "PRINCESS ALLIANCE")), 1e-9)
        assertEquals(1.0, rules.score(mapOf("group" to "the bored corona kids")), 1e-9)
        assertEquals(0.0, rules.score(mapOf("group" to "someone else")), 1e-9)
    }

    @Test
    fun `matches regex with flags`() {
        val rules = SelExpr.compile("matches(group, '/(princess|corona)/i')", vars)
        assertEquals(1.0, rules.score(mapOf("group" to "Princess Alliance")), 1e-9)
        assertEquals(0.0, rules.score(mapOf("group" to "Other Group")), 1e-9)
    }

    @Test
    fun `min max abs arithmetic`() {
        val rules = SelExpr.compile("min(pages, 100) + max(number - 50, 0) + abs(0 - 5) == 105", vars)
        // pages=80: 80 + max(0,0)=0 + 5 = 85
        assertEquals(0.0, rules.score(mapOf("pages" to 80, "number" to 50)), 1e-9)
        val trueRules = SelExpr.compile("min(pages, 100) + abs(0 - 5) == 85", vars)
        assertEquals(1.0, trueRules.score(mapOf("pages" to 80)), 1e-9)
    }

    @Test
    fun `not and or keywords`() {
        val rules = SelExpr.compile("not official and (mangadex or includes(group, 'asura'))", vars)
        val base = mapOf("official" to false, "mangadex" to false, "group" to "")
        assertEquals(1.0, rules.score(base + mapOf("mangadex" to true)), 1e-9)
        assertEquals(1.0, rules.score(base + mapOf("group" to "asura scans")), 1e-9)
        assertEquals(0.0, rules.score(base + mapOf("official" to true, "mangadex" to true)), 1e-9)
        assertEquals(0.0, rules.score(base), 1e-9)
    }

    @Test
    fun `comment-only lines and comments are skipped`() {
        val source = """
            // just a comment
            /* leading comment */ group == 'asura'
            /* named */ -5: partial
        """.trimIndent()
        val rules = SelExpr.compile(source, vars)
        val variables = rules.variables
        assertEquals(setOf("group", "partial"), variables)
        assertEquals(-5.0 + 1.0, rules.score(mapOf("group" to "asura", "partial" to true)), 1e-9)
    }

    @Test
    fun `variables set for lazy fetch decisions`() {
        val rules = SelExpr.compile("official or number > 1", vars)
        assertEquals(setOf("official", "number"), rules.variables)
    }

    @Test
    fun `unknown variable is a compile error`() {
        val error = assertThrows(Exception::class.java) {
            SelExpr.compile("groupp == 'x'", vars)
        }
        assertTrue(error.message!!.contains("Unknown variable 'groupp'"))
        assertTrue(error.message!!.contains("line 1"))
    }

    @Test
    fun `unknown function is a compile error`() {
        val error = assertThrows(Exception::class.java) {
            SelExpr.compile("fancy(group)", vars)
        }
        assertTrue(error.message!!.contains("Unknown function 'fancy'"))
    }

    @Test
    fun `syntax errors are compile errors with line numbers`() {
        val source = """
            group == 'a'
            group ==
        """.trimIndent()
        val error = assertThrows(Exception::class.java) {
            SelExpr.compile(source, vars)
        }
        assertTrue(error.message!!.contains("line 2"))
    }

    @Test
    fun `missing binding throws at runtime with rule name`() {
        val source = "/* check */ pages > 10"
        val rules = SelExpr.compile(source, vars)
        val error = assertThrows(Exception::class.java) {
            rules.score(mapOf("group" to "x"))
        }
        assertTrue(error.message!!.contains("pages"))
    }

    @Test
    fun `invalid regex fails at compile time`() {
        val error = assertThrows(Exception::class.java) {
            SelExpr.compile("matches(group, '/(unclosed/')", vars)
        }
        assertTrue(error.message!!.contains("Invalid regex"))
    }

    @Test
    fun `invalid regex flags fail at compile time`() {
        val error = assertThrows(Exception::class.java) {
            SelExpr.compile("matches(group, '/x/q')", vars)
        }
        assertTrue(error.message!!.contains("Invalid regex flags"))
    }

    @Test
    fun `numeric literals with comparisons`() {
        val rules = SelExpr.compile("number >= 3.5 and number <= 10", vars)
        assertEquals(1.0, rules.score(mapOf("number" to 4.0)), 1e-9)
        assertEquals(0.0, rules.score(mapOf("number" to 3.0)), 1e-9)
    }

    @Test
    fun `division by zero yields zero`() {
        val rules = SelExpr.compile("10 / 0 == 0", vars)
        assertEquals(1.0, rules.score(emptyMap()), 1e-9)
    }

    @Test
    fun `null and empty values are falsy`() {
        val rules = SelExpr.compile("group", vars)
        assertEquals(0.0, rules.score(mapOf("group" to null)), 1e-9)
        assertEquals(0.0, rules.score(mapOf("group" to "")), 1e-9)
        val listRules = SelExpr.compile("genres", vars)
        assertEquals(0.0, listRules.score(mapOf("genres" to emptyList<String>())), 1e-9)
    }

    @Test
    fun `empty source throws`() {
        assertThrows(Exception::class.java) {
            SelExpr.compile("   \n// comment\n", vars)
        }
    }

    @Test
    fun `line comments ignored inside expressions`() {
        val rules = SelExpr.compile("group == 'asura' // trailing comment", vars)
        assertEquals(1.0, rules.score(mapOf("group" to "asura")), 1e-9)
    }

    @Test
    fun `unary minus and parenthesized expressions`() {
        val rules = SelExpr.compile("-(number - 10) > 0", vars)
        assertEquals(1.0, rules.score(mapOf("number" to 5.0)), 1e-9)
        assertEquals(0.0, rules.score(mapOf("number" to 15.0)), 1e-9)
    }

    @Test
    fun `number type coercion`() {
        // Int/Long/Float/Boolean from Kotlin call sites all coerce
        val rules = SelExpr.compile("pages == 200 and year > 2020 and official", vars)
        assertEquals(
            1.0,
            rules.score(mapOf("pages" to 200, "year" to 2022L, "official" to true)),
            1e-9,
        )
    }

    @Test
    fun `rofan style example from user`() {
        val source = """
            /* Rofan specialists */ 900: 'Romance Fantasy' in genres and includes(group, 'Princess Alliance', 'Bored Corona Kids', 'GoddessCafe')
            /* defaults */ 10: official
            /* penalize partials */ -10000: partial
        """.trimIndent()
        val rules = SelExpr.compile(source, vars)
        assertEquals(
            910.0,
            rules.score(
                mapOf(
                    "genres" to listOf("Romance Fantasy"),
                    "group" to "GoddessCafe Scans",
                    "official" to true,
                    "partial" to false,
                ),
            ),
            1e-9,
        )
        assertEquals(
            -9090.0,
            rules.score(
                mapOf(
                    "genres" to listOf("Romance Fantasy"),
                    "group" to "GoddessCafe Scans",
                    "official" to true,
                    "partial" to true,
                ),
            ),
            1e-9,
        )
    }
}
