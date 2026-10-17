package com.halo.moontone.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Expressive shape scale: noticeably rounder than baseline M3, with the new
 * `largeIncreased` / `extraLargeIncreased` / `extraExtraLarge` steps that
 * Material 3 Expressive introduced for hero surfaces and sheets.
 */
val MoonToneShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    largeIncreased = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(34.dp),
    extraLargeIncreased = RoundedCornerShape(38.dp),
    extraExtraLarge = RoundedCornerShape(44.dp),
)

/**
 * Named surface shapes used across MoonTone screens so cards, hero blocks and
 * input fields stay visually related even when implemented in different files.
 */
object MoonToneShape {
    /** Default content card (device cards, settings groups, log panel). */
    val card: Shape = RoundedCornerShape(28.dp)

    /** Hero block on Home — the largest, boldest surface in the app. */
    val hero: Shape = RoundedCornerShape(36.dp)

    /** Small tonal badge behind an icon (squircle-ish). */
    val badge: Shape = RoundedCornerShape(18.dp)

    /** Text fields and compact controls. */
    val input: Shape = RoundedCornerShape(20.dp)

    /** Fully rounded pills: chips, status pills, primary CTAs. */
    val pill: Shape = RoundedCornerShape(percent = 50)

    /** Bottom-sheet top corners used by modal sheets. */
    val sheet: Shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp)
}
