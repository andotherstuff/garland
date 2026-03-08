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
        assertTrue(mainLayout.contains("@string/action_group_quick_checks_label"))
        assertTrue(mainLayout.contains("@string/action_group_document_actions_label"))
        assertTrue(mainLayout.contains("@string/action_group_danger_zone_label"))
        assertTrue(mainLayout.contains("@+id/actionSafetyNoteText"))
        assertTrue(mainLayout.contains("@style/Widget.Garland.DangerButton"))
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
        assertTrue(diagnosticsLayout.contains("@+id/diagnosticsTroubleshootingSummaryText"))
        assertTrue(diagnosticsLayout.contains("@+id/diagnosticsEvidenceHintText"))
        assertTrue(diagnosticsLayout.contains("MaterialCardView"))
    }

    @Test
    fun diagnosticsScreenUsesMissionControlHeroAndSectionPanels() {
        val diagnosticsLayout = projectFile("app", "src", "main", "res", "layout", "activity_diagnostics.xml")

        assertTrue(diagnosticsLayout.contains("diagnosticsStatusChip"))
        assertTrue(diagnosticsLayout.contains("diagnosticsHeadlineText"))
        assertTrue(diagnosticsLayout.contains("diagnosticsSummaryText"))
        assertTrue(diagnosticsLayout.contains("@style/Widget.Garland.Card"))
    }

    @Test
    fun layoutsUseSharedSpacingTokensAndRefinedTypographyStyles() {
        val dimensXml = projectFile("app", "src", "main", "res", "values", "dimens.xml")
        val stylesXml = projectFile("app", "src", "main", "res", "values", "styles.xml")
        val mainLayout = projectFile("app", "src", "main", "res", "layout", "activity_main.xml")
        val diagnosticsLayout = projectFile("app", "src", "main", "res", "layout", "activity_diagnostics.xml")

        assertTrue(dimensXml.contains("garland_screen_padding"))
        assertTrue(dimensXml.contains("garland_card_spacing"))
        assertTrue(stylesXml.contains("TextAppearance.Garland.DisplayTitle"))
        assertTrue(stylesXml.contains("TextAppearance.Garland.BodySupport"))
        assertTrue(mainLayout.contains("@dimen/garland_screen_padding"))
        assertTrue(mainLayout.contains("@style/TextAppearance.Garland.DisplayTitle"))
        assertTrue(mainLayout.contains("@style/TextAppearance.Garland.BodySupport"))
        assertTrue(diagnosticsLayout.contains("@dimen/garland_screen_padding"))
        assertTrue(diagnosticsLayout.contains("@style/TextAppearance.Garland.DisplayTitle"))
        assertTrue(diagnosticsLayout.contains("@style/TextAppearance.Garland.BodySupport"))
    }

    @Test
    fun actionsAndDocumentPickersUseStrongerAffordanceStyles() {
        val stylesXml = projectFile("app", "src", "main", "res", "values", "styles.xml")
        val mainLayout = projectFile("app", "src", "main", "res", "layout", "activity_main.xml")
        val diagnosticsLayout = projectFile("app", "src", "main", "res", "layout", "activity_diagnostics.xml")
        val mainActivity = projectFile("app", "src", "main", "java", "com", "andotherstuff", "garland", "MainActivity.kt")
        val diagnosticsActivity = projectFile("app", "src", "main", "java", "com", "andotherstuff", "garland", "DiagnosticsActivity.kt")

        assertTrue(stylesXml.contains("Widget.Garland.ActionButton.Primary"))
        assertTrue(stylesXml.contains("Widget.Garland.DocumentPickerButton"))
        assertTrue(mainLayout.contains("@style/Widget.Garland.ActionButton.Primary"))
        assertTrue(diagnosticsLayout.contains("@style/Widget.Garland.ActionButton.Primary"))
        assertTrue(mainActivity.contains("R.style.Widget_Garland_DocumentPickerButton"))
        assertTrue(diagnosticsActivity.contains("R.style.Widget_Garland_DocumentPickerButton"))
    }

    @Test
    fun diagnosticsViewsUseReadableDetailTypography() {
        val stylesXml = projectFile("app", "src", "main", "res", "values", "styles.xml")
        val mainLayout = projectFile("app", "src", "main", "res", "layout", "activity_main.xml")
        val diagnosticsLayout = projectFile("app", "src", "main", "res", "layout", "activity_diagnostics.xml")

        assertTrue(stylesXml.contains("TextAppearance.Garland.DiagnosticsBody"))
        assertTrue(mainLayout.contains("@style/TextAppearance.Garland.DiagnosticsBody"))
        assertTrue(diagnosticsLayout.contains("@style/TextAppearance.Garland.DiagnosticsBody"))
        assertTrue(mainLayout.contains("@+id/activeDocumentDiagnosticsText"))
        assertTrue(mainLayout.contains("@+id/activeDocumentUploadsText"))
        assertTrue(mainLayout.contains("@+id/activeDocumentRelaysText"))
        assertTrue(diagnosticsLayout.contains("@+id/diagnosticsOverviewText"))
        assertTrue(diagnosticsLayout.contains("@+id/diagnosticsUploadsText"))
        assertTrue(diagnosticsLayout.contains("@+id/diagnosticsRelaysText"))
        assertTrue(diagnosticsLayout.contains("@+id/diagnosticsHistoryText"))
        assertTrue(diagnosticsLayout.contains("@+id/diagnosticsTroubleshootingText"))
    }

    @Test
    fun styleAndColorTokensExposeSemanticDiagnosticStates() {
        val stylesXml = projectFile("app", "src", "main", "res", "values", "styles.xml")
        val colorsXml = projectFile("app", "src", "main", "res", "values", "colors.xml")

        assertTrue(stylesXml.contains("Widget.Garland.DangerButton"))
        assertTrue(stylesXml.contains("Widget.Garland.SubtleButton"))
        assertTrue(stylesXml.contains("TextAppearance.Garland.StatusChip"))
        assertTrue(colorsXml.contains("garland_surface_soft"))
        assertTrue(colorsXml.contains("garland_surface_strong"))
        assertTrue(colorsXml.contains("garland_danger_surface"))
    }

    private fun projectFile(vararg segments: String): String {
        val cwd = Path.of(System.getProperty("user.dir"))
        val repoRoot = if (cwd.resolve("app").toFile().exists()) cwd else cwd.parent
        return repoRoot.resolve(Path.of("", *segments)).toFile().readText()
    }
}
