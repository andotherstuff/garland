package com.andotherstuff.garland

import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiThemeContractTest {
    @Test
    fun themeUsesDarkMaterialParentAndDarkSystemBars() {
        val themeXml = projectFile("app", "src", "main", "res", "values", "themes.xml")

        assertTrue(themeXml.contains("Theme.Material3.Dark.NoActionBar"))
        assertFalse(themeXml.contains("@android:color/white"))
    }

    @Test
    fun mainAndDiagnosticsLayoutsAvoidHardcodedWhiteBackgrounds() {
        val mainLayout = projectFile("app", "src", "main", "res", "layout", "activity_main.xml")
        val diagnosticsLayout = projectFile("app", "src", "main", "res", "layout", "activity_diagnostics.xml")

        assertFalse(mainLayout.contains("@android:color/white"))
        assertFalse(diagnosticsLayout.contains("@android:color/white"))
    }

    @Test
    fun mainScreenUsesSectionCardsAndTroubleshootingLabel() {
        val mainLayout = projectFile("app", "src", "main", "res", "layout", "activity_main.xml")

        assertTrue(mainLayout.contains("com.google.android.material.card.MaterialCardView"))
        assertTrue(mainLayout.contains("@string/troubleshooting_actions_label"))
        assertTrue(mainLayout.contains("@+id/mainStatusChip"))
        assertTrue(mainLayout.contains("@+id/mainStatusHeadlineText"))
        assertTrue(mainLayout.contains("@+id/mainStatusSummaryText"))
        assertTrue(mainLayout.contains("@+id/mainNextStepsText"))
    }

    @Test
    fun appUsesBundledGarlandLogoForLauncherAndHeroImage() {
        val manifestXml = projectFile("app", "src", "main", "AndroidManifest.xml")
        val mainLayout = projectFile("app", "src", "main", "res", "layout", "activity_main.xml")

        assertTrue(manifestXml.contains("@drawable/garland_logo"))
        assertTrue(mainLayout.contains("@drawable/garland_logo"))
    }

    @Test
    fun diagnosticsScreenUsesMissionControlStatusAndNextStepsPanels() {
        val diagnosticsLayout = projectFile("app", "src", "main", "res", "layout", "activity_diagnostics.xml")

        assertTrue(diagnosticsLayout.contains("@+id/diagnosticsStatusChip"))
        assertTrue(diagnosticsLayout.contains("@+id/diagnosticsHeadlineText"))
        assertTrue(diagnosticsLayout.contains("@+id/diagnosticsSummaryText"))
        assertTrue(diagnosticsLayout.contains("@+id/diagnosticsNextStepsText"))
        assertTrue(diagnosticsLayout.contains("MaterialCardView"))
    }

    private fun projectFile(vararg segments: String): String {
        val cwd = Path.of(System.getProperty("user.dir"))
        val repoRoot = if (cwd.resolve("app").toFile().exists()) cwd else cwd.parent
        return repoRoot.resolve(Path.of("", *segments)).toFile().readText()
    }
}
