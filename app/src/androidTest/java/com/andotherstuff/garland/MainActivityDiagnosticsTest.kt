package com.andotherstuff.garland

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.CoreMatchers.containsString
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityDiagnosticsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val store = LocalDocumentStore(targetContext)

    @Before
    fun setUp() {
        clearAppState()
    }

    @After
    fun tearDown() {
        clearAppState()
    }

    @Test
    fun showsStructuredUploadAndRelayDiagnosticsForSelectedDocument() {
        val diagnosticsJson = DocumentSyncDiagnosticsCodec.encode(
            DocumentSyncDiagnostics(
                uploads = listOf(
                    DocumentEndpointDiagnostic("https://blossom.one", "ok", "Uploaded share a1"),
                    DocumentEndpointDiagnostic("https://blossom.two", "ok", "Uploaded share a2"),
                ),
                relays = listOf(
                    DocumentEndpointDiagnostic("wss://relay.one", "ok", "Relay accepted commit event"),
                    DocumentEndpointDiagnostic("wss://relay.two", "failed", "timeout"),
                ),
            )
        )
        val document = store.upsertPreparedDocument(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            content = "hello world".toByteArray(),
            uploadPlanJson = sampleUploadPlanJson(),
        )
        store.updateUploadDiagnostics(
            documentId = document.documentId,
            status = "relay-published-partial",
            message = "Published to 1/2 relays; failed: wss://relay.two (timeout)",
            diagnosticsJson = diagnosticsJson,
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.activeDocumentText)).check(matches(withText(containsString("note.txt"))))
            onView(withId(R.id.activeDocumentDiagnosticsText)).check(matches(withText(containsString("Status: relay-published-partial"))))
            onView(withId(R.id.activeDocumentDiagnosticsText)).check(matches(withText(containsString("Blocks: 2"))))
            onView(withId(R.id.activeDocumentUploadsLabel)).check(matches(withText("Uploads (2/2 ok)")))
            onView(withId(R.id.activeDocumentUploadsText)).check(matches(withText(containsString("blossom.one [ok] Uploaded share a1"))))
            onView(withId(R.id.activeDocumentRelaysLabel)).check(matches(withText("Relays (1/2 failed)")))
            onView(withId(R.id.activeDocumentRelaysText)).check(matches(withText(containsString("relay.two [failed] timeout"))))
        }
    }

    @Test
    fun hidesUploadAndRelaySectionsWhenDocumentHasNoStructuredDiagnostics() {
        store.upsertPreparedDocument(
            documentId = "doc-no-diagnostics",
            displayName = "plain-note.txt",
            mimeType = "text/plain",
            content = "hello world".toByteArray(),
            uploadPlanJson = sampleUploadPlanJson(documentId = "doc-no-diagnostics"),
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.activeDocumentText)).check(matches(withText(containsString("plain-note.txt"))))
            onView(withId(R.id.activeDocumentDiagnosticsText)).check(matches(withText(containsString("Last result: No sync result yet"))))
            onView(withId(R.id.activeDocumentUploadsLabel)).check(matches(withEffectiveVisibility(GONE)))
            onView(withId(R.id.activeDocumentUploadsText)).check(matches(withEffectiveVisibility(GONE)))
            onView(withId(R.id.activeDocumentRelaysLabel)).check(matches(withEffectiveVisibility(GONE)))
            onView(withId(R.id.activeDocumentRelaysText)).check(matches(withEffectiveVisibility(GONE)))
        }
    }

    @Test
    fun showsPlannedServersLabelBeforeUploadDiagnosticsExist() {
        store.upsertPreparedDocument(
            documentId = "doc-planned",
            displayName = "planned-note.txt",
            mimeType = "text/plain",
            content = "hello world".toByteArray(),
            uploadPlanJson = sampleUploadPlanJson(documentId = "doc-planned"),
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.activeDocumentText)).check(matches(withText(containsString("planned-note.txt"))))
            onView(withId(R.id.activeDocumentUploadsLabel)).check(matches(withText("Planned servers")))
            onView(withId(R.id.activeDocumentUploadsText)).check(matches(withText(containsString("blossom.one"))))
            onView(withId(R.id.activeDocumentRelaysLabel)).check(matches(withEffectiveVisibility(GONE)))
        }
    }

    @Test
    fun selectingDifferentDocumentRefreshesDiagnosticsSections() {
        store.upsertPreparedDocument(
            documentId = "doc-plain",
            displayName = "plain-note.txt",
            mimeType = "text/plain",
            content = "hello world".toByteArray(),
            uploadPlanJson = sampleUploadPlanJson(documentId = "doc-plain"),
        )
        val diagnosticsJson = DocumentSyncDiagnosticsCodec.encode(
            DocumentSyncDiagnostics(
                uploads = listOf(DocumentEndpointDiagnostic("https://blossom.one", "ok", "Uploaded share a1")),
                relays = listOf(DocumentEndpointDiagnostic("wss://relay.two", "failed", "timeout")),
            )
        )
        val detailed = store.upsertPreparedDocument(
            documentId = "doc-detailed",
            displayName = "detailed-note.txt",
            mimeType = "text/plain",
            content = "hello world".toByteArray(),
            uploadPlanJson = sampleUploadPlanJson(documentId = "doc-detailed"),
        )
        store.updateUploadDiagnostics(
            documentId = detailed.documentId,
            status = "relay-published-partial",
            message = "Published to 1/2 relays; failed: wss://relay.two (timeout)",
            diagnosticsJson = diagnosticsJson,
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.activeDocumentText)).check(matches(withText(containsString("detailed-note.txt"))))
            onView(withId(R.id.activeDocumentUploadsLabel)).check(matches(isDisplayed()))
            onView(withText(containsString("plain-note.txt"))).perform(click())
            onView(withId(R.id.activeDocumentText)).check(matches(withText(containsString("plain-note.txt"))))
            onView(withId(R.id.activeDocumentUploadsLabel)).check(matches(withEffectiveVisibility(GONE)))
            onView(withId(R.id.activeDocumentRelaysLabel)).check(matches(withEffectiveVisibility(GONE)))
            onView(withText(containsString("detailed-note.txt"))).perform(click())
            onView(withId(R.id.activeDocumentText)).check(matches(withText(containsString("detailed-note.txt"))))
            onView(withId(R.id.activeDocumentUploadsText)).check(matches(withText(containsString("blossom.one [ok] Uploaded share a1"))))
            onView(withId(R.id.activeDocumentRelaysText)).check(matches(withText(containsString("relay.two [failed] timeout"))))
        }
    }

    private fun sampleUploadPlanJson(documentId: String = "doc123"): String {
        return """
            {
              "ok": true,
              "plan": {
                "manifest": {
                  "document_id": "$documentId",
                  "mime_type": "text/plain",
                  "size_bytes": 11,
                  "sha256_hex": "abc123def456",
                  "blocks": [
                    {"servers": ["https://blossom.one", "https://blossom.two"]},
                    {"servers": ["https://blossom.one", "https://blossom.two"]}
                  ]
                }
              }
            }
        """.trimIndent()
    }

    private fun clearAppState() {
        targetContext.deleteSharedPreferences("garland-session")
        targetContext.filesDir.resolve("garland-documents").deleteRecursively()
    }
}
