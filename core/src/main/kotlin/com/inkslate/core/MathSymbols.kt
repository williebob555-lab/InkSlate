package com.inkslate.core

/**
 * Characters that are painful to reach on a tablet keyboard, and no easier on a laptop one.
 *
 * Everything here is a real Unicode character, placed as a text object rather than an image, so
 * it stays selectable, restyleable and searchable once exported.
 *
 * Shared because the list is part of what a document can contain: a palette that differed between
 * the two builds would mean a symbol available on one machine and unreachable on the other, in
 * files that move between them.
 */
object MathSymbols {

    data class Group(val name: String, val symbols: List<String>)

    val groups = listOf(
        Group(
            "Operators",
            listOf("×", "÷", "±", "∓", "·", "√", "∛", "∞", "∝", "≈", "≠", "≡", "≤", "≥", "≪", "≫")
        ),
        Group(
            "Calculus",
            listOf("∫", "∬", "∭", "∮", "∂", "∇", "Δ", "δ", "lim", "Σ", "Π", "→", "↦", "′", "″", "…")
        ),
        Group(
            "Greek",
            listOf(
                "α", "β", "γ", "δ", "ε", "θ", "λ", "μ", "π", "ρ", "σ", "τ", "φ", "χ", "ψ", "ω",
                "Γ", "Θ", "Λ", "Ξ", "Φ", "Ψ", "Ω"
            )
        ),
        Group(
            "Sets and logic",
            listOf("∈", "∉", "⊂", "⊆", "⊃", "⊇", "∪", "∩", "∅", "∀", "∃", "¬", "∧", "∨", "⇒", "⇔")
        ),
        Group(
            "Superscript",
            listOf("⁰", "¹", "²", "³", "⁴", "⁵", "⁶", "⁷", "⁸", "⁹", "ⁿ", "⁺", "⁻", "ᵀ", "°", "∠")
        ),
        Group(
            "Subscript",
            listOf("₀", "₁", "₂", "₃", "₄", "₅", "₆", "₇", "₈", "₉", "ₐ", "ₑ", "ᵢ", "ⱼ", "ₙ", "ₓ")
        ),
        Group(
            "Fractions",
            listOf("½", "⅓", "⅔", "¼", "¾", "⅕", "⅙", "⅛", "⅜", "⅝", "⅞", "⁄")
        ),
        Group(
            "Chemistry and units",
            listOf("⇌", "↑", "↓", "Å", "µ", "Ω", "℃", "℉", "‰", "∴", "∵", "⊥", "∥", "≅", "≜", "∎")
        )
    )
}
