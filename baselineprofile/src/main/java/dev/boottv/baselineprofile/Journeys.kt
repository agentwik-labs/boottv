package dev.boottv.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

const val PACKAGE = "dev.boottv"

/** Starts BootTV and waits until Home's rows are on screen. */
fun MacrobenchmarkScope.startHome() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.text("On Now")), 20_000)
    device.waitForIdle()
}

private fun MacrobenchmarkScope.press(times: Int, key: () -> Unit) = repeat(times) { key(); Thread.sleep(250) }

/** What a viewer does most with the remote: along and down the Home rows, then back to the top. */
fun MacrobenchmarkScope.browseHome() {
    press(6) { device.pressDPadRight() }
    press(4) { device.pressDPadDown() }
    press(4) { device.pressDPadRight() }
    press(5) { device.pressDPadUp() }
}

/** Opens a side-menu section ([downs] entries below Home), browses its rows, and comes back to Home. */
fun MacrobenchmarkScope.visitSection(downs: Int, waitText: String) {
    device.pressDPadLeft(); Thread.sleep(500)
    press(downs) { device.pressDPadDown() }
    device.pressDPadCenter()
    device.wait(Until.hasObject(By.textContains(waitText)), 15_000)
    device.waitForIdle()
    press(3) { device.pressDPadDown() }
    press(4) { device.pressDPadRight() }
    device.pressDPadLeft(); Thread.sleep(500)
    device.pressDPadLeft(); Thread.sleep(500)
    press(downs) { device.pressDPadUp() }
    device.pressDPadCenter()
    device.wait(Until.hasObject(By.text("On Now")), 15_000)
}
