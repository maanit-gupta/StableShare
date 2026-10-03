package com.maanit.stableshare.ui.mascot

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Where the plane is drawn: its centre (px, in the parent's coordinates), rotationZ and alpha. */
data class PlanePose(val x: Float, val y: Float, val rotation: Float, val alpha: Float = 1f) {
    fun lerp(to: PlanePose, t: Float) = PlanePose(
        x + (to.x - x) * t,
        y + (to.y - y) * t,
        rotation + (to.rotation - rotation) * t,
        alpha + (to.alpha - alpha) * t,
    )
}

/**
 * Pure geometry for the plane (UI-SPEC §2.1, §5.8.3). Angles are degrees; θ is 0 at 12 o'clock
 * and grows clockwise; headings are measured clockwise from +x in screen coordinates.
 */
object PlaneMath {
    /** The drawable's nose points 18.7° above the horizontal. */
    const val NOSE_OFFSET_DEG = 18.7f

    /** Nose tilt below the flight direction in the FAILED pose. */
    const val FAILED_TILT_DEG = 35f

    fun rotationForHeading(headingDeg: Float): Float = headingDeg + NOSE_OFFSET_DEG

    /** On a circle, flying clockwise: heading = θ. */
    fun onRing(thetaDeg: Float, cx: Float, cy: Float, radius: Float): PlanePose {
        val r = Math.toRadians(thetaDeg.toDouble())
        return PlanePose(
            x = cx + radius * sin(r).toFloat(),
            y = cy - radius * cos(r).toFloat(),
            rotation = rotationForHeading(thetaDeg),
        )
    }

    /** Point and radial unit vector at θ, for the ±2 dp bob perpendicular to the ring. */
    fun radial(thetaDeg: Float): Pair<Float, Float> {
        val r = Math.toRadians(thetaDeg.toDouble())
        return sin(r).toFloat() to -cos(r).toFloat()
    }

    /** At the bottom of the ring (θ = 180°, flying left) with the nose tilted [FAILED_TILT_DEG] down. */
    fun failed(cx: Float, cy: Float, radius: Float): PlanePose =
        onRing(180f, cx, cy, radius).copy(rotation = rotationForHeading(180f - FAILED_TILT_DEG))

    /** On an ellipse around the cloud, flying clockwise, nose following the tangent. */
    fun onEllipse(phiDeg: Float, cx: Float, cy: Float, rx: Float, ry: Float): PlanePose {
        val r = Math.toRadians(phiDeg.toDouble())
        val dx = rx * cos(r)
        val dy = ry * sin(r)
        val heading = Math.toDegrees(atan2(dy, dx)).toFloat()
        return PlanePose(
            x = cx + rx * sin(r).toFloat(),
            y = cy - ry * cos(r).toFloat(),
            rotation = rotationForHeading(heading),
        )
    }
}

/** Cloud landmarks as fractions of the 240 × 200 viewport every cloud layer shares (§2.1). */
object CloudGeometry {
    const val ASPECT = 240f / 200f
    const val RIGHT_ARM_PIVOT_X = 0.85f
    const val RIGHT_ARM_PIVOT_Y = 0.61f
    const val LEFT_ARM_PIVOT_X = 0.19f
    const val LEFT_ARM_PIVOT_Y = 0.64f

    /** Top of the big centre bump (circle at (124, 86), r 52, outline included). */
    const val TOP_X = 124f / 240f
    const val TOP_Y = 32f / 200f

    /** Orbit used when the plane loops around the cloud (splash, onboarding page 1). */
    const val ORBIT_CX = 124f / 240f
    const val ORBIT_CY = 98f / 200f
    const val ORBIT_RX = 124f / 240f
    const val ORBIT_RY = 92f / 200f

    /** Vertical strips of the three rain drops (viewport x 88–112, 112–136, 136–160). */
    val RAIN_STRIPS = listOf(88f / 240f to 112f / 240f, 112f / 240f to 136f / 240f, 136f / 240f to 160f / 240f)

    /** Transform origin used for breathing: the cloud's bottom centre. */
    const val BREATHE_PIVOT_X = 0.5f
    const val BREATHE_PIVOT_Y = 158f / 200f
}
