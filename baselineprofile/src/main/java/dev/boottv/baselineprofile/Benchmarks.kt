package dev.boottv.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup and Home scrolling, without and with the Baseline Profile. "None" is what a fresh sideloaded install
 * runs like before Android compiles anything.
 */
@RunWith(AndroidJUnit4::class)
class Benchmarks {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test fun startupNoProfile() = startup(CompilationMode.None())
    @Test fun startupWithProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))
    @Test fun scrollNoProfile() = scroll(CompilationMode.None())
    @Test fun scrollWithProfile() = scroll(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 8,
    ) { startHome() }

    private fun scroll(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = { startHome() },
    ) { browseHome() }
}
