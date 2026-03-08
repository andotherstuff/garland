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
    }

    @Test
    fun diagnosticsScreenUsesMissionControlHeroAndSectionPanels() {
        val diagnosticsLayout = projectFile("app", "src", "main", "res", "layout", "activity_diagnostics.xml")

        assertTrue(diagnosticsLayout.contains("diagnosticsHeroCard"))
        assertTrue(diagnosticsLayout.contains("diagnosticsHeadlineToneText"))
        assertTrue(diagnosticsLayout.contains("diagnosticsHeadlineText"))
        assertTrue(diagnosticsLayout.contains("diagnosticsSummaryText"))
        assertTrue(diagnosticsLayout.contains("@style/Widget.Garland.PanelCard"))
    }

    @Test
    fun styleAndColorTokensExposeSemanticDiagnosticStates() {
        val stylesXml = projectFile("app", "src", "main", "res", "values", "styles.xml")
        val colorsXml = projectFile("app", "src", "main", "res", "values", "colors.xml")

        assertTrue(stylesXml.contains("Widget.Garland.HeroCard"))
        assertTrue(stylesXml.contains("Widget.Garland.StatusChip.Success"))
        assertTrue(stylesXml.contains("Widget.Garland.StatusChip.Warning"))
        assertTrue(stylesXml.contains("Widget.Garland.StatusChip.Error"))
        assertTrue(colorsXml.contains("garland_info"))
        assertTrue(colorsXml.contains("garland_surface_high"))
    }

    private fun projectFile(vararg segments: String): String {
        val cwd = Path.of(System.getProperty("user.dir"))
        val repoRoot = if (cwd.resolve("app").toFile().exists()) cwd else cwd.parent
        return repoRoot.resolve(Path.of("", *segments)).toFile().readText()
    }
}
