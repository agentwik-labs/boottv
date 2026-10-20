package dev.boottv.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the code BootTV runs on its main paths (startup, Home rows, side menu, Live TV guide, Free Movies,
 * Cartoons) into app/src/release/generated/baselineProfiles. Run with tools/profile.sh.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        startHome()
        browseHome()
        visitSection(downs = 2, waitText = "Today") // Live TV guide
        visitSection(downs = 4, waitText = "FREE MOVIES")
        visitSection(downs = 5, waitText = "CARTOONS")
    }
}
