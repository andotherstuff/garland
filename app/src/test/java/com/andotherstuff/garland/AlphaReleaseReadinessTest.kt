package com.andotherstuff.garland

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AlphaReleaseReadinessTest {
    @Test
    fun noDeviceVerificationScriptRunsCoverageAndLintGates() {
        val script = repoFile("automation/verify_alpha_no_device.sh").readText()

        assertTrue(script.contains("jacocoDebugUnitTestReport"))
        assertTrue(script.contains("report_android_unit_coverage.py"))
        assertTrue(script.contains("lintDebug"))
    }

    @Test
    fun releaseDocsMentionCoverageGate() {
        val expectedCommand = "./gradlew jacocoDebugUnitTestReport"

        // All task tracking consolidated in TODO.md — old docs redirect there
        assertTrue(repoFile("TODO.md").readText().contains(expectedCommand))
    }

    @Test
    fun gitignoreExcludesAutoloopLogs() {
        val gitignore = repoFile(".gitignore").readText()

        assertTrue(gitignore.contains("/.autoloop-logs/"))
    }

    private fun repoFile(relativePath: String): File {
        var current = File(System.getProperty("user.dir") ?: error("Missing user.dir")).absoluteFile
        repeat(5) {
            val candidate = current.resolve(relativePath)
            if (candidate.exists()) {
                return candidate
            }
            val parent = current.parentFile ?: return@repeat
            current = parent
        }
        error("Could not locate repo file: $relativePath")
    }
}
