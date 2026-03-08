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

    private fun projectFile(vararg segments: String): String {
        val cwd = Path.of(System.getProperty("user.dir"))
        val repoRoot = if (cwd.resolve("app").toFile().exists()) cwd else cwd.parent
        return repoRoot.resolve(Path.of("", *segments)).toFile().readText()
    }
}
