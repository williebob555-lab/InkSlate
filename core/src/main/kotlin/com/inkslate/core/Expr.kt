package com.inkslate.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * The equations a graph can be asked to draw.
 *
 * Written here rather than pulled in, and deliberately small: it has to give the same answer on
 * the tablet and on the laptop, down to the last digit, because the two draw the same document -
 * and a library that rounds differently on two platforms would draw a curve that moves when the
 * file is opened on the other one.
 *
 * What it accepts is what somebody writing homework writes: `2x`, `3sin(x)+1`, `x^2 - 4`,
 * `sqrt(x)`, `1/(x-2)`. Multiplication can be left out where it is obvious - `2x`, `3(x+1)`,
 * `2sin(x)` - because that is how the same expression is written on paper.
 */
object Expr {

    /** A parsed equation, or the reason it could not be read. */
    class Parsed internal constructor(private val root: Node?, val error: String?) {
        val ok: Boolean get() = root != null

        /** The value at [x], or NaN where the equation has none - a gap, or a divide by zero. */
        fun at(x: Double): Double = root?.let {
            runCatching { it.eval(x) }.getOrDefault(Double.NaN)
        } ?: Double.NaN
    }

    fun parse(text: String, degrees: Boolean = false): Parsed {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Parsed(null, "Nothing to draw yet")
        return runCatching {
            val parser = Parser(trimmed, degrees)
            val node = parser.expression()
            parser.expectEnd()
            Parsed(node, null)
        }.getOrElse { Parsed(null, it.message ?: "That equation could not be read") }
    }

    /** The names this understands, for a hint under the box someone types into. */
    val functionNames: List<String> = listOf(
        "sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh",
        "sqrt", "abs", "exp", "ln", "log", "log10", "sign", "floor", "ceil", "round"
    )

    // ---- the tree ------------------------------------------------------------

    internal interface Node {
        fun eval(x: Double): Double
    }

    private class Constant(val value: Double) : Node {
        override fun eval(x: Double) = value
    }

    private object Variable : Node {
        override fun eval(x: Double) = x
    }

    private class Unary(val arg: Node, val op: (Double) -> Double) : Node {
        override fun eval(x: Double) = op(arg.eval(x))
    }

    private class Binary(val left: Node, val right: Node, val op: (Double, Double) -> Double) : Node {
        override fun eval(x: Double) = op(left.eval(x), right.eval(x))
    }

    // ---- reading it ----------------------------------------------------------

    private class Parser(private val text: String, private val degrees: Boolean) {
        private var at = 0

        private fun skipSpace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        private fun peek(): Char? {
            skipSpace()
            return if (at < text.length) text[at] else null
        }

        private fun take(c: Char): Boolean {
            if (peek() == c) { at++; return true }
            return false
        }

        fun expectEnd() {
            val c = peek() ?: return
            fail("There is a stray '$c' at the end")
        }

        private fun fail(why: String): Nothing = throw IllegalArgumentException(why)

        /** Sums: the loosest binding, so it is read last and evaluated first. */
        fun expression(): Node {
            var left = term()
            while (true) {
                left = when {
                    take('+') -> Binary(left, term()) { a, b -> a + b }
                    take('-') -> Binary(left, term()) { a, b -> a - b }
                    else -> return left
                }
            }
        }

        private fun term(): Node {
            var left = power()
            while (true) {
                val c = peek()
                left = when {
                    take('*') -> Binary(left, power()) { a, b -> a * b }
                    take('/') -> Binary(left, power()) { a, b -> a / b }
                    // "2x", "3sin(x)", "2(x+1)": the multiplication sign people leave out.
                    c != null && (c.isDigit() || c.isLetter() || c == '(') ->
                        Binary(left, power()) { a, b -> a * b }
                    else -> return left
                }
            }
        }

        private fun power(): Node {
            val base = unary()
            // Right to left, so 2^3^2 is 2^(3^2), which is what it means in writing.
            if (take('^')) return Binary(base, power()) { a, b -> a.pow(b) }
            return base
        }

        private fun unary(): Node {
            if (take('-')) return Unary(unary()) { -it }
            if (take('+')) return unary()
            return atom()
        }

        private fun atom(): Node {
            val c = peek() ?: fail("The equation stops before it is finished")
            if (c == '(') {
                at++
                val inner = expression()
                if (!take(')')) fail("A '(' is never closed")
                return inner
            }
            if (c.isDigit() || c == '.') return Constant(number())
            if (c.isLetter()) return named()
            fail("'$c' is not something this can read")
        }

        private fun number(): Double {
            val start = at
            while (at < text.length && (text[at].isDigit() || text[at] == '.')) at++
            val raw = text.substring(start, at)
            return raw.toDoubleOrNull() ?: fail("'$raw' is not a number")
        }

        private fun named(): Node {
            val start = at
            while (at < text.length && (text[at].isLetter() || text[at].isDigit())) at++
            val name = text.substring(start, at).lowercase()

            when (name) {
                "x", "t" -> return Variable
                "pi" -> return Constant(PI)
                "tau" -> return Constant(2 * PI)
                "e" -> return Constant(kotlin.math.E)
            }

            if (!take('(')) fail("'$name' needs a number or a bracket after it")
            val arg = expression()
            if (!take(')')) fail("'$name(' is never closed")

            // Degrees where someone is working in degrees: the angle goes in as degrees and
            // comes back out of the inverses as degrees, which is the whole point of the switch.
            val toRadians = if (degrees) PI / 180.0 else 1.0
            val fromRadians = if (degrees) 180.0 / PI else 1.0
            val op: (Double) -> Double = when (name) {
                "sin" -> { v -> sin(v * toRadians) }
                "cos" -> { v -> cos(v * toRadians) }
                "tan" -> { v -> tan(v * toRadians) }
                "asin" -> { v -> asin(v) * fromRadians }
                "acos" -> { v -> acos(v) * fromRadians }
                "atan" -> { v -> atan(v) * fromRadians }
                "sinh" -> ::sinh
                "cosh" -> ::cosh
                "tanh" -> ::tanh
                "sqrt" -> ::sqrt
                "abs" -> ::abs
                "exp" -> ::exp
                "ln" -> ::ln
                "log", "log10" -> ::log10
                "sign" -> ::sign
                "floor" -> ::floor
                "ceil" -> ::ceil
                "round" -> { v -> round(v) }
                else -> fail("This does not know a function called '$name'")
            }
            return Unary(arg, op)
        }
    }
}
