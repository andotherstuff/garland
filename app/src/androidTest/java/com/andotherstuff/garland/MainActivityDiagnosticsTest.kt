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
            onView(withId(R.id.activeDocumentText)).check(matches(withText(containsString("Relay published partial"))))
            onView(withId(R.id.activeDocumentDiagnosticsText)).check(matches(withText(containsString("Status: Relay published partial"))))
            onView(withId(R.id.activeDocumentDiagnosticsText)).check(matches(withText(containsString("Blocks: 2"))))
            onView(withId(R.id.activeDocumentDiagnosticsText)).check(matches(withText(containsString("Servers: 2"))))
            onView(withId(R.id.activeDocumentUploadsLabel)).check(matches(withText("Uploads (2/2 ok)")))
            onView(withId(R.id.activeDocumentUploadsText)).check(matches(withText(containsString("blossom.one [OK] Uploaded share a1"))))
            onView(withId(R.id.activeDocumentRelaysLabel)).check(matches(withText("Relays (1/2 failed)")))
            onView(withId(R.id.activeDocumentRelaysText)).check(matches(withText(containsString("relay.two [Failed] timeout"))))
        }
    }

    @Test
    fun showsReadableStructuredUploadHttpStatusInDiagnostics() {
        val diagnosticsJson = DocumentSyncDiagnosticsCodec.encode(
            DocumentSyncDiagnostics(
                uploads = listOf(
                    DocumentEndpointDiagnostic(
                        "https://blossom.two",
                        "http-500",
                        "Upload failed on https://blossom.two with HTTP 500",
                    ),
                ),
            )
        )
        val document = store.upsertPreparedDocument(
            documentId = "doc-structured-upload-http-failure",
            displayName = "structured-upload-http-failure.txt",
            mimeType = "text/plain",
            content = "hello world".toByteArray(),
            uploadPlanJson = sampleUploadPlanJson(documentId = "doc-structured-upload-http-failure"),
        )
        store.updateUploadDiagnostics(
            documentId = document.documentId,
            status = "upload-http-500",
            message = "Upload failed on https://blossom.two with HTTP 500",
            diagnosticsJson = diagnosticsJson,
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.activeDocumentUploadsLabel)).check(matches(withText("Uploads (1/1 failed)")))
            onView(withId(R.id.activeDocumentUploadsText)).check(matches(withText(containsString("blossom.two [HTTP 500] Upload failed on https://blossom.two with HTTP 500"))))
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
    fun showsLegacyRelayFailuresWhenStructuredDiagnosticsAreMissing() {
        val document = store.upsertPreparedDocument(
            documentId = "doc-legacy-failure",
            displayName = "legacy-note.txt",
            mimeType = "text/plain",
            content = "hello world".toByteArray(),
            uploadPlanJson = sampleUploadPlanJson(documentId = "doc-legacy-failure"),
        )
        store.updateUploadStatus(
            document.documentId,
            "relay-published-partial",
            "Published to 1/2 relays; failed: wss://relay.two (timeout)",
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.activeDocumentText)).check(matches(withText(containsString("legacy-note.txt"))))
            onView(withId(R.id.activeDocumentRelaysLabel)).check(matches(withText("Relays (1 failed)")))
            onView(withId(R.id.activeDocumentRelaysText)).check(matches(withText(containsString("relay.two (timeout)"))))
        }
    }

    @Test
    fun showsLegacyUploadFailuresWhenStructuredDiagnosticsAreMissing() {
        val document = store.upsertPreparedDocument(
            documentId = "doc-legacy-upload-failure",
            displayName = "legacy-upload-note.txt",
            mimeType = "text/plain",
            content = "hello world".toByteArray(),
            uploadPlanJson = sampleUploadPlanJson(documentId = "doc-legacy-upload-failure"),
        )
        store.updateUploadStatus(
            document.documentId,
            "upload-http-500",
            "Upload failed on https://blossom.two with HTTP 500",
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.activeDocumentText)).check(matches(withText(containsString("legacy-upload-note.txt"))))
            onView(withId(R.id.activeDocumentUploadsLabel)).check(matches(withText("Uploads (1 failed)")))
            onView(withId(R.id.activeDocumentUploadsText)).check(matches(withText(containsString("blossom.two with HTTP 500"))))
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
    fun showsDistinctServerCountForMultiBlockPlanWithUnevenServers() {
        store.upsertPreparedDocument(
            documentId = "doc-uneven-servers",
            displayName = "uneven-servers.txt",
            mimeType = "text/plain",
            content = "hello world".toByteArray(),
            uploadPlanJson = sampleUploadPlanJson(
                documentId = "doc-uneven-servers",
                blockServers = listOf(
                    listOf("https://blossom.one"),
                    listOf("https://blossom.one", "https://blossom.two", "https://blossom.three"),
                ),
            ),
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.activeDocumentText)).check(matches(withText(containsString("uneven-servers.txt"))))
            onView(withId(R.id.activeDocumentDetailText)).check(matches(withText(containsString("2 block(s) • 3 server(s)"))))
            onView(withId(R.id.activeDocumentUploadsLabel)).check(matches(withText("Planned servers")))
            onView(withId(R.id.activeDocumentUploadsText)).check(matches(withText(containsString("blossom.three"))))
        }
    }

    @Test
    fun preservesStructuredDiagnosticsAfterStatusOnlyQueuedUpdate() {
        val diagnosticsJson = DocumentSyncDiagnosticsCodec.encode(
            DocumentSyncDiagnostics(
                uploads = listOf(
                    DocumentEndpointDiagnostic("https://blossom.one", "ok", "Uploaded share a1"),
                    DocumentEndpointDiagnostic("https://blossom.two", "failed", "HTTP 500"),
                ),
                relays = listOf(
                    DocumentEndpointDiagnostic("wss://relay.one", "ok", "Relay accepted commit event"),
                    DocumentEndpointDiagnostic("wss://relay.two", "failed", "timeout"),
                ),
            )
        )
        val document = store.upsertPreparedDocument(
            documentId = "doc-preserved-diagnostics",
            displayName = "preserved-diagnostics.txt",
            mimeType = "text/plain",
            content = "hello world".toByteArray(),
            uploadPlanJson = sampleUploadPlanJson(documentId = "doc-preserved-diagnostics"),
        )
        store.updateUploadDiagnostics(
            documentId = document.documentId,
            status = "relay-publish-failed",
            message = "Published to 1/2 relays; failed: wss://relay.two (timeout)",
            diagnosticsJson = diagnosticsJson,
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.activeDocumentText)).check(matches(withText(containsString("preserved-diagnostics.txt"))))
            onView(withId(R.id.activeDocumentUploadsLabel)).check(matches(withText("Uploads (1/2 failed)")))
            onView(withId(R.id.activeDocumentUploadsText)).check(matches(withText(containsString("blossom.two [Failed] HTTP 500"))))
            onView(withId(R.id.activeDocumentRelaysLabel)).check(matches(withText("Relays (1/2 failed)")))
            onView(withId(R.id.activeDocumentRelaysText)).check(matches(withText(containsString("relay.two [Failed] timeout"))))

            store.updateUploadStatus(document.documentId, "sync-queued", "Queued Garland sync in background")
            scenario.recreate()

            onView(withId(R.id.activeDocumentDiagnosticsText)).check(matches(withText(containsString("Status: Sync queued"))))
            onView(withId(R.id.activeDocumentDiagnosticsText)).check(matches(withText(containsString("Last result: Queued Garland sync in background"))))
            onView(withId(R.id.activeDocumentUploadsLabel)).check(matches(withText("Uploads (1/2 failed)")))
            onView(withId(R.id.activeDocumentUploadsText)).check(matches(withText(containsString("blossom.two [Failed] HTTP 500"))))
            onView(withId(R.id.activeDocumentRelaysLabel)).check(matches(withText("Relays (1/2 failed)")))
            onView(withId(R.id.activeDocumentRelaysText)).check(matches(withText(containsString("relay.two [Failed] timeout"))))
        }
    }

    @Test
    fun preservesStructuredDiagnosticsAfterStatusOnlyRunningUpdate() {
        val diagnosticsJson = DocumentSyncDiagnosticsCodec.encode(
            DocumentSyncDiagnostics(
                uploads = listOf(
                    DocumentEndpointDiagnostic("https://blossom.one", "ok", "Uploaded share a1"),
                    DocumentEndpointDiagnostic("https://blossom.two", "failed", "HTTP 500"),
                ),
                relays = listOf(
                    DocumentEndpointDiagnostic("wss://relay.one", "ok", "Relay accepted commit event"),
                    DocumentEndpointDiagnostic("wss://relay.two", "failed", "timeout"),
                ),
            )
        )
        val document = store.upsertPreparedDocument(
            documentId = "doc-running-diagnostics",
            displayName = "running-diagnostics.txt",
            mimeType = "text/plain",
            content = "hello world".toByteArray(),
            uploadPlanJson = sampleUploadPlanJson(documentId = "doc-running-diagnostics"),
        )
        store.updateUploadDiagnostics(
            documentId = document.documentId,
            status = "relay-publish-failed",
            message = "Published to 1/2 relays; failed: wss://relay.two (timeout)",
            diagnosticsJson = diagnosticsJson,
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.activeDocumentText)).check(matches(withText(containsString("running-diagnostics.txt"))))
            onView(withId(R.id.activeDocumentUploadsLabel)).check(matches(withText("Uploads (1/2 failed)")))
            onView(withId(R.id.activeDocumentUploadsText)).check(matches(withText(containsString("blossom.two [Failed] HTTP 500"))))
            onView(withId(R.id.activeDocumentRelaysLabel)).check(matches(withText("Relays (1/2 failed)")))
            onView(withId(R.id.activeDocumentRelaysText)).check(matches(withText(containsString("relay.two [Failed] timeout"))))

            store.updateUploadStatus(document.documentId, "sync-running", "Running Garland sync in background")
            scenario.recreate()

            onView(withId(R.id.activeDocumentDiagnosticsText)).check(matches(withText(containsString("Status: Sync running"))))
            onView(withId(R.id.activeDocumentDiagnosticsText)).check(matches(withText(containsString("Last result: Running Garland sync in background"))))
            onView(withId(R.id.activeDocumentUploadsLabel)).check(matches(withText("Uploads (1/2 failed)")))
            onView(withId(R.id.activeDocumentUploadsText)).check(matches(withText(containsString("blossom.two [Failed] HTTP 500"))))
            onView(withId(R.id.activeDocumentRelaysLabel)).check(matches(withText("Relays (1/2 failed)")))
            onView(withId(R.id.activeDocumentRelaysText)).check(matches(withText(containsString("relay.two [Failed] timeout"))))
        }
    }

    @Test
    fun preservesLastResultWhenStatusOnlyUpdateOmitsReplacementMessage() {
        val diagnosticsJson = DocumentSyncDiagnosticsCodec.encode(
            DocumentSyncDiagnostics(
                uploads = listOf(
                    DocumentEndpointDiagnostic("https://blossom.one", "ok", "Uploaded share a1"),
                    DocumentEndpointDiagnostic("https://blossom.two", "failed", "HTTP 500"),
                ),
                relays = listOf(
                    DocumentEndpointDiagnostic("wss://relay.one", "ok", "Relay accepted commit event"),
                    DocumentEndpointDiagnostic("wss://relay.two", "failed", "timeout"),
                ),
            )
        )
        val document = store.upsertPreparedDocument(
            documentId = "doc-preserved-last-result",
            displayName = "preserved-last-result.txt",
            mimeType = "text/plain",
            content = "hello world".toByteArray(),
            uploadPlanJson = sampleUploadPlanJson(documentId = "doc-preserved-last-result"),
        )
        store.updateUploadDiagnostics(
            documentId = document.documentId,
            status = "relay-publish-failed",
            message = "Published to 1/2 relays; failed: wss://relay.two (timeout)",
            diagnosticsJson = diagnosticsJson,
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.activeDocumentDiagnosticsText)).check(matches(withText(containsString("Last result: Published to 1/2 relays"))))

            store.updateUploadStatus(document.documentId, "sync-running")
            scenario.recreate()

            onView(withId(R.id.activeDocumentDiagnosticsText)).check(matches(withText(containsString("Status: Sync running"))))
            onView(withId(R.id.activeDocumentDiagnosticsText)).check(matches(withText(containsString("Last result: Published to 1/2 relays"))))
        }
    }

    @Test
    fun preservesStructuredDiagnosticsAfterRestoreQueuedAndRunningUpdates() {
        val diagnosticsJson = DocumentSyncDiagnosticsCodec.encode(
            DocumentSyncDiagnostics(
                uploads = listOf(
                    DocumentEndpointDiagnostic("https://blossom.one", "ok", "Uploaded share a1"),
                    DocumentEndpointDiagnostic("https://blossom.two", "failed", "HTTP 500"),
                ),
                relays = listOf(
                    DocumentEndpointDiagnostic("wss://relay.one", "ok", "Relay accepted commit event"),
                    DocumentEndpointDiagnostic("wss://relay.two", "failed", "timeout"),
                ),
            )
        )
        val document = store.upsertPreparedDocument(
            documentId = "doc-restore-diagnostics",
            displayName = "restore-diagnostics.txt",
            mimeType = "text/plain",
            content = "hello world".toByteArray(),
            uploadPlanJson = sampleUploadPlanJson(documentId = "doc-restore-diagnostics"),
        )
        store.updateUploadDiagnostics(
            documentId = document.documentId,
            status = "relay-publish-failed",
            message = "Published to 1/2 relays; failed: wss://relay.two (timeout)",
            diagnosticsJson = diagnosticsJson,
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.activeDocumentText)).check(matches(withText(containsString("restore-diagnostics.txt"))))
            onView(withId(R.id.activeDocumentUploadsLabel)).check(matches(withText("Uploads (1/2 failed)")))
            onView(withId(R.id.activeDocumentUploadsText)).check(matches(withText(containsString("blossom.two [Failed] HTTP 500"))))
            onView(withId(R.id.activeDocumentRelaysLabel)).check(matches(withText("Relays (1/2 failed)")))
            onView(withId(R.id.activeDocumentRelaysText)).check(matches(withText(containsString("relay.two [Failed] timeout"))))

            store.updateUploadStatus(document.documentId, "restore-queued", "Queued Garland restore in background")
            scenario.recreate()

            onView(withId(R.id.activeDocumentDiagnosticsText)).check(matches(withText(containsString("Status: Restore queued"))))
            onView(withId(R.id.activeDocumentDiagnosticsText)).check(matches(withText(containsString("Last result: Queued Garland restore in background"))))
            onView(withId(R.id.activeDocumentUploadsLabel)).check(matches(withText("Uploads (1/2 failed)")))
            onView(withId(R.id.activeDocumentUploadsText)).check(matches(withText(containsString("blossom.two [Failed] HTTP 500"))))
            onView(withId(R.id.activeDocumentRelaysLabel)).check(matches(withText("Relays (1/2 failed)")))
            onView(withId(R.id.activeDocumentRelaysText)).check(matches(withText(containsString("relay.two [Failed] timeout"))))

            store.updateUploadStatus(document.documentId, "restore-running", "Restoring Garland document in background")
            scenario.recreate()

            onView(withId(R.id.activeDocumentDiagnosticsText)).check(matches(withText(containsString("Status: Restore running"))))
            onView(withId(R.id.activeDocumentDiagnosticsText)).check(matches(withText(containsString("Last result: Restoring Garland document in background"))))
            onView(withId(R.id.activeDocumentUploadsLabel)).check(matches(withText("Uploads (1/2 failed)")))
            onView(withId(R.id.activeDocumentUploadsText)).check(matches(withText(containsString("blossom.two [Failed] HTTP 500"))))
            onView(withId(R.id.activeDocumentRelaysLabel)).check(matches(withText("Relays (1/2 failed)")))
            onView(withId(R.id.activeDocumentRelaysText)).check(matches(withText(containsString("relay.two [Failed] timeout"))))
        }
    }

    @Test
    fun preservesStructuredDiagnosticsAfterRestoreFailureUpdate() {
        val diagnosticsJson = DocumentSyncDiagnosticsCodec.encode(
            DocumentSyncDiagnostics(
                uploads = listOf(
                    DocumentEndpointDiagnostic("https://blossom.one", "ok", "Uploaded share a1"),
                    DocumentEndpointDiagnostic("https://blossom.two", "failed", "HTTP 500"),
                ),
                relays = listOf(
                    DocumentEndpointDiagnostic("wss://relay.one", "ok", "Relay accepted commit event"),
                    DocumentEndpointDiagnostic("wss://relay.two", "failed", "timeout"),
                ),
            )
        )
        val document = store.upsertPreparedDocument(
            documentId = "doc-restore-failure-diagnostics",
            displayName = "restore-failure-diagnostics.txt",
            mimeType = "text/plain",
            content = "hello world".toByteArray(),
            uploadPlanJson = sampleUploadPlanJson(documentId = "doc-restore-failure-diagnostics"),
        )
        store.updateUploadDiagnostics(
            documentId = document.documentId,
            status = "relay-publish-failed",
            message = "Published to 1/2 relays; failed: wss://relay.two (timeout)",
            diagnosticsJson = diagnosticsJson,
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.activeDocumentText)).check(matches(withText(containsString("restore-failure-diagnostics.txt"))))
            onView(withId(R.id.activeDocumentUploadsLabel)).check(matches(withText("Uploads (1/2 failed)")))
            onView(withId(R.id.activeDocumentUploadsText)).check(matches(withText(containsString("blossom.two [Failed] HTTP 500"))))
            onView(withId(R.id.activeDocumentRelaysLabel)).check(matches(withText("Relays (1/2 failed)")))
            onView(withId(R.id.activeDocumentRelaysText)).check(matches(withText(containsString("relay.two [Failed] timeout"))))

            store.updateUploadStatus(document.documentId, "restore-failed", "Load identity before background restore")
            scenario.recreate()

            onView(withId(R.id.activeDocumentDiagnosticsText)).check(matches(withText(containsString("Status: Restore failed"))))
            onView(withId(R.id.activeDocumentDiagnosticsText)).check(matches(withText(containsString("Last result: Load identity before background restore"))))
            onView(withId(R.id.activeDocumentUploadsLabel)).check(matches(withText("Uploads (1/2 failed)")))
            onView(withId(R.id.activeDocumentUploadsText)).check(matches(withText(containsString("blossom.two [Failed] HTTP 500"))))
            onView(withId(R.id.activeDocumentRelaysLabel)).check(matches(withText("Relays (1/2 failed)")))
            onView(withId(R.id.activeDocumentRelaysText)).check(matches(withText(containsString("relay.two [Failed] timeout"))))
        }
    }

    @Test
    fun preservesStructuredDiagnosticsAfterRestoreSuccessUpdate() {
        val diagnosticsJson = DocumentSyncDiagnosticsCodec.encode(
            DocumentSyncDiagnostics(
                uploads = listOf(
                    DocumentEndpointDiagnostic("https://blossom.one", "ok", "Uploaded share a1"),
                    DocumentEndpointDiagnostic("https://blossom.two", "failed", "HTTP 500"),
                ),
                relays = listOf(
                    DocumentEndpointDiagnostic("wss://relay.one", "ok", "Relay accepted commit event"),
                    DocumentEndpointDiagnostic("wss://relay.two", "failed", "timeout"),
                ),
            )
        )
        val document = store.upsertPreparedDocument(
            documentId = "doc-restore-success-diagnostics",
            displayName = "restore-success-diagnostics.txt",
            mimeType = "text/plain",
            content = "hello world".toByteArray(),
            uploadPlanJson = sampleUploadPlanJson(documentId = "doc-restore-success-diagnostics"),
        )
        store.updateUploadDiagnostics(
            documentId = document.documentId,
            status = "relay-publish-failed",
            message = "Published to 1/2 relays; failed: wss://relay.two (timeout)",
            diagnosticsJson = diagnosticsJson,
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.activeDocumentText)).check(matches(withText(containsString("restore-success-diagnostics.txt"))))
            onView(withId(R.id.activeDocumentUploadsLabel)).check(matches(withText("Uploads (1/2 failed)")))
            onView(withId(R.id.activeDocumentUploadsText)).check(matches(withText(containsString("blossom.two [Failed] HTTP 500"))))
            onView(withId(R.id.activeDocumentRelaysLabel)).check(matches(withText("Relays (1/2 failed)")))
            onView(withId(R.id.activeDocumentRelaysText)).check(matches(withText(containsString("relay.two [Failed] timeout"))))

            store.updateUploadStatus(document.documentId, "download-restored", "Restored 11 bytes from 2 Garland block(s)")
            scenario.recreate()

            onView(withId(R.id.activeDocumentDiagnosticsText)).check(matches(withText(containsString("Status: Download restored"))))
            onView(withId(R.id.activeDocumentDiagnosticsText)).check(matches(withText(containsString("Last result: Restored 11 bytes from 2 Garland block(s)"))))
            onView(withId(R.id.activeDocumentUploadsLabel)).check(matches(withText("Uploads (1/2 failed)")))
            onView(withId(R.id.activeDocumentUploadsText)).check(matches(withText(containsString("blossom.two [Failed] HTTP 500"))))
            onView(withId(R.id.activeDocumentRelaysLabel)).check(matches(withText("Relays (1/2 failed)")))
            onView(withId(R.id.activeDocumentRelaysText)).check(matches(withText(containsString("relay.two [Failed] timeout"))))
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
            onView(withId(R.id.statusText)).check(matches(withText(containsString("Pending local write"))))
            onView(withId(R.id.activeDocumentUploadsLabel)).check(matches(withEffectiveVisibility(GONE)))
            onView(withId(R.id.activeDocumentRelaysLabel)).check(matches(withEffectiveVisibility(GONE)))
            onView(withText(containsString("detailed-note.txt"))).perform(click())
            onView(withId(R.id.activeDocumentText)).check(matches(withText(containsString("detailed-note.txt"))))
            onView(withId(R.id.statusText)).check(matches(withText(containsString("Relay published partial"))))
            onView(withId(R.id.activeDocumentUploadsText)).check(matches(withText(containsString("blossom.one [OK] Uploaded share a1"))))
            onView(withId(R.id.activeDocumentRelaysText)).check(matches(withText(containsString("relay.two [Failed] timeout"))))
        }
    }

    private fun sampleUploadPlanJson(
        documentId: String = "doc123",
        blockServers: List<List<String>> = listOf(
            listOf("https://blossom.one", "https://blossom.two"),
            listOf("https://blossom.one", "https://blossom.two"),
        ),
    ): String {
        val blocksJson = blockServers.joinToString(",\n") { servers ->
            val serversJson = servers.joinToString(", ") { server -> "\"$server\"" }
            "                    {\"servers\": [$serversJson]}"
        }
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
$blocksJson
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
