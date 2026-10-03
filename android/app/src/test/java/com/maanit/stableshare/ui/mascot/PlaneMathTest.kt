package com.maanit.stableshare.ui.mascot

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaneMathTest {
    private val eps = 1e-3f

    @Test
    fun `ring angle 0 is 12 o'clock flying right`() {
        val p = PlaneMath.onRing(0f, cx = 100f, cy = 100f, radius = 50f)
        assertEquals(100f, p.x, eps)
        assertEquals(50f, p.y, eps)
        assertEquals(18.7f, p.rotation, eps)
    }

    @Test
    fun `ring angle 90 is 3 o'clock flying down`() {
        val p = PlaneMath.onRing(90f, 100f, 100f, 50f)
        assertEquals(150f, p.x, eps)
        assertEquals(100f, p.y, eps)
        assertEquals(108.7f, p.rotation, eps)
    }

    @Test
    fun `failed pose sits at the bottom with the nose tilted 35 degrees down`() {
        val p = PlaneMath.failed(100f, 100f, 50f)
        assertEquals(100f, p.x, eps)
        assertEquals(150f, p.y, eps)
        // Flying left (heading 180°); 35° nose-down turns the heading to 145° (down-left).
        assertEquals(145f + 18.7f, p.rotation, eps)
    }

    @Test
    fun `ellipse with equal radii agrees with the ring`() {
        for (theta in listOf(0f, 45f, 135f, 270f)) {
            val ring = PlaneMath.onRing(theta, 10f, 20f, 30f)
            val ellipse = PlaneMath.onEllipse(theta, 10f, 20f, 30f, 30f)
            assertEquals(ring.x, ellipse.x, eps)
            assertEquals(ring.y, ellipse.y, eps)
            assertEquals(((ring.rotation % 360) + 360) % 360, ((ellipse.rotation % 360) + 360) % 360, eps)
        }
    }

    @Test
    fun `splash entry starts off-screen left and lands on the perch`() {
        val perched = perchedPose(240f, 200f, 12f)
        val start = entryPose(0f, 240f, 200f, perched)
        val end = entryPose(1f, 240f, 200f, perched)
        assert(start.x < 0f) { "starts left of the mascot box: ${start.x}" }
        assertEquals(perched.x, end.x, eps)
        assertEquals(perched.y, end.y, eps)
        assertEquals(0f, end.rotation, eps)
    }
}
