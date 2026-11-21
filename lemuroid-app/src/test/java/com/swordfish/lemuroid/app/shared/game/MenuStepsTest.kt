package com.swordfish.lemuroid.app.shared.game

import com.swordfish.lemuroid.app.shared.game.BaseGameScreenViewModel.MenuKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MenuStepsTest {
    @Test
    fun `one step per press, however the finger wobbles`() {
        val steps = MenuSteps()
        assertEquals(MenuKey.RIGHT, steps.step(0.9f, 0.1f, 1_000))
        // Still held, drifting up and back: no more steps.
        assertNull(steps.step(0.6f, 0.65f, 1_050))
        assertNull(steps.step(0.4f, 0.1f, 1_100))
        assertNull(steps.step(0.95f, 0f, 1_400))
        // Let go, press again: one more step.
        assertNull(steps.step(0.1f, 0.1f, 1_500))
        assertEquals(MenuKey.RIGHT, steps.step(1f, 0f, 1_600))
    }

    @Test
    fun `quick taps closer than the step time count once`() {
        val steps = MenuSteps()
        assertEquals(MenuKey.LEFT, steps.step(-1f, 0f, 0))
        assertNull(steps.step(0f, 0f, 40))
        assertNull(steps.step(-1f, 0f, 80))
        assertNull(steps.step(0f, 0f, 120))
        assertEquals(MenuKey.LEFT, steps.step(-1f, 0f, MenuSteps.STEP_MS + 10))
    }

    @Test
    fun `up and down`() {
        val steps = MenuSteps()
        assertEquals(MenuKey.UP, steps.step(0f, 1f, 0))
        steps.step(0f, 0f, 200)
        assertEquals(MenuKey.DOWN, steps.step(0.1f, -1f, 400))
    }
}
