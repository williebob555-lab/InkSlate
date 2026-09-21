package com.inkslate.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.ImageComposeScene
import com.inkslate.core.AngleLabel
import com.inkslate.core.DiscreteStyle
import com.inkslate.core.Stamps
import com.inkslate.core.Stroke
import org.junit.Assume
import org.junit.Test
import java.io.File
import com.inkslate.core.Box as InkBox

/**
 * Pictures of the new stamps, saved so they can be looked at.
 *
 * Not an assertion about pixels - the geometry is tested in `:core`. This exists because a graph
 * can be arithmetically perfect and still unreadable: labels on top of each other, samples off the
 * bottom of the axes, a unit circle with its angles written over the circle. Those are only ever
 * found by looking, and looking is something that has to be possible without a tablet in hand.
 *
 * Writes into the directory named by `inkslate.looks`, and does nothing at all when it is not set.
 */
class StampLookTest {

    private val into: File? = System.getProperty("inkslate.looks")?.let { File(it) }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun shoot(name: String, wide: Int, high: Int, marks: List<Stroke>) {
        val target = into ?: return
        target.mkdirs()
        val scene = ImageComposeScene(width = wide, height = high, density = Density(1f)) {
            Box(Modifier.size(wide.dp, high.dp).background(Color.White)) {
                val measurer = rememberTextMeasurer()
                Canvas(Modifier.fillMaxSize()) {
                    marks.forEach { stroke ->
                        if (stroke.kind == Stroke.Kind.TEXT) {
                            drawTextStroke(stroke, measurer)
                        } else {
                            drawStroke(stroke, cached = false)
                        }
                    }
                }
            }
        }
        try {
            val png = scene.render().encodeToData() ?: error("the scene did not encode")
            File(target, "$name.png").writeBytes(png.bytes)
        } finally {
            scene.close()
        }
    }

    private var ids = 0

    private fun stamp(kind: Stamps.Kind, o: Stamps.StampOptions, wide: Int, high: Int): List<Stroke> =
        Stamps.build(
            kind, InkBox(20f, 20f, wide - 20f, high - 20f), page = 0,
            options = Stamps.sanitise(kind, o), group = "look"
        ) { "s${ids++}" }

    @Test
    fun `the new stamps, drawn`() {
        Assume.assumeTrue("set -Dinkslate.looks=<dir> to write the pictures", into != null)

        val wave = Stamps.Kind.SINE.defaults

        shoot("sine-continuous", 640, 420, stamp(Stamps.Kind.SINE, wave, 640, 420))

        shoot(
            "sine-stems", 640, 420,
            stamp(Stamps.Kind.SINE, wave.copy(sampleEvery = 0.05f), 640, 420)
        )
        shoot(
            "sine-stems-only", 640, 420,
            stamp(
                Stamps.Kind.SINE,
                wave.copy(sampleEvery = 0.05f, showContinuous = false, markMax = false, markMin = false, markZeros = false),
                640, 420
            )
        )
        shoot(
            "sine-held", 640, 420,
            stamp(
                Stamps.Kind.SINE,
                wave.copy(sampleEvery = 0.0625f, discreteStyle = DiscreteStyle.STEPS),
                640, 420
            )
        )
        shoot(
            "sine-quantised", 640, 420,
            stamp(
                Stamps.Kind.SINE,
                wave.copy(sampleEvery = 0.04f, quantize = true, quantBits = 3, discreteStyle = DiscreteStyle.DOTS),
                640, 420
            )
        )
        // A 9 Hz wave sampled at 10 Hz: the samples trace a 1 Hz wave, which is the point.
        shoot(
            "sine-aliased", 640, 420,
            stamp(
                Stamps.Kind.SINE,
                wave.copy(
                    omega = (2.0 * Math.PI * 9).toFloat(), sampleEvery = 0.1f,
                    rangeTo = 2f, step = 0.25f, smoothness = 900,
                    markMax = false, markMin = false, markZeros = false
                ),
                640, 420
            )
        )

        val circle = Stamps.Kind.UNIT_CIRCLE.defaults
        shoot("circle-radians", 460, 460, stamp(Stamps.Kind.UNIT_CIRCLE, circle, 460, 460))
        shoot(
            "circle-quarters", 460, 460,
            stamp(
                Stamps.Kind.UNIT_CIRCLE,
                circle.copy(angleStep = 4, angleLabels = AngleLabel.BOTH, angleDots = true),
                460, 460
            )
        )
        shoot(
            "circle-coordinates", 560, 560,
            stamp(
                Stamps.Kind.UNIT_CIRCLE,
                circle.copy(angleStep = 4, angleLabels = AngleLabel.COORDINATES),
                560, 560
            )
        )
        shoot(
            "circle-marked", 460, 460,
            stamp(Stamps.Kind.UNIT_CIRCLE, circle.copy(markAngle = 210f), 460, 460)
        )
        shoot(
            "circle-twelfths", 560, 560,
            stamp(Stamps.Kind.UNIT_CIRCLE, circle.copy(angleStep = 12), 560, 560)
        )
    }
}
