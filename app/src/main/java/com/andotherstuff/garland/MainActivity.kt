package com.andotherstuff.garland

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.andotherstuff.garland.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var session: GarlandSessionStore
    private lateinit var store: LocalDocumentStore
    private lateinit var workScheduler: GarlandWorkScheduler
    private var privateKeyHex: String? = null
    private var preparedDocumentId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        session = GarlandSessionStore(applicationContext)
        store = LocalDocumentStore(applicationContext)
        workScheduler = GarlandWorkScheduler(applicationContext)

        bindDefaults()
        binding.statusText.text = getString(R.string.app_boot_status)
        privateKeyHex = session.loadPrivateKeyHex()
        bindLatestDocument()

        binding.deriveButton.setOnClickListener {
            val response = JSONObject(
                NativeBridge.deriveIdentity(
                    binding.mnemonicInput.text?.toString().orEmpty(),
                    binding.passphraseInput.text?.toString().orEmpty(),
                )
            )

            binding.statusText.text = if (response.optBoolean("ok")) {
                privateKeyHex = response.optString("private_key_hex")
                session.savePrivateKeyHex(privateKeyHex.orEmpty())
                getString(R.string.identity_loaded, response.optString("nsec"))
            } else {
                privateKeyHex = null
                getString(R.string.identity_error, response.optString("error"))
            }
        }

        binding.prepareUploadButton.setOnClickListener {
            val privateKey = privateKeyHex
            if (privateKey.isNullOrBlank()) {
                binding.statusText.text = getString(R.string.prepare_requires_identity)
                return@setOnClickListener
            }

            session.saveRelays(currentRelays())

            val requestJson = GarlandConfig.buildPrepareWriteRequestJson(
                privateKeyHex = privateKey,
                displayName = binding.fileNameInput.text?.toString().orEmpty().ifBlank { "note.txt" },
                mimeType = binding.mimeTypeInput.text?.toString().orEmpty().ifBlank { "text/plain" },
                content = binding.contentInput.text?.toString().orEmpty().toByteArray(),
                blossomServers = currentBlossomServers(),
                createdAt = System.currentTimeMillis() / 1000,
            )
            session.saveBlossomServers(currentBlossomServers())

            val response = JSONObject(NativeBridge.prepareSingleBlockWrite(requestJson))
            binding.statusText.text = if (response.optBoolean("ok")) {
                val plan = response.getJSONObject("plan")
                val documentId = plan.getString("document_id")
                store.upsertPreparedDocument(
                    documentId = documentId,
                    displayName = binding.fileNameInput.text?.toString().orEmpty().ifBlank { "note.txt" },
                    mimeType = binding.mimeTypeInput.text?.toString().orEmpty().ifBlank { "text/plain" },
                    content = binding.contentInput.text?.toString().orEmpty().toByteArray(),
                    uploadPlanJson = response.toString(),
                )
                selectDocument(store.readRecord(documentId), false)
                getString(
                    R.string.upload_prepared,
                    documentId,
                    plan.getJSONArray("uploads").length()
                )
            } else {
                getString(R.string.upload_prepare_error, response.optString("error"))
            }
        }

        binding.executeUploadButton.setOnClickListener {
            val documentId = preparedDocumentId
            if (documentId.isNullOrBlank()) {
                binding.statusText.text = getString(R.string.upload_requires_prepared_document)
                return@setOnClickListener
            }

            executeUpload(documentId, getString(R.string.upload_running, documentId))
        }

        binding.refreshDocumentsButton.setOnClickListener {
            refreshDocumentList(preparedDocumentId)
        }

        binding.syncDocumentsButton.setOnClickListener {
            val relays = currentRelays()
            session.saveRelays(relays)
            workScheduler.enqueuePendingSync(relays)
            refreshDocumentList(preparedDocumentId)
            binding.statusText.text = getString(R.string.sync_documents_queued)
        }

        binding.retryUploadButton.setOnClickListener {
            val documentId = preparedDocumentId
            if (documentId.isNullOrBlank()) {
                binding.statusText.text = getString(R.string.upload_requires_prepared_document)
                return@setOnClickListener
            }

            executeUpload(documentId, getString(R.string.upload_retry_running, documentId))
        }

        binding.deleteDocumentButton.setOnClickListener {
            val document = preparedDocumentId?.let { store.readRecord(it) }
            if (document == null) {
                binding.statusText.text = getString(R.string.document_delete_requires_selection)
                return@setOnClickListener
            }

            store.deleteDocument(document.documentId)
            clearDocumentInputs()
            selectDocument(store.latestDocument(), false)
            binding.statusText.text = getString(R.string.document_deleted, document.displayName)
        }

        binding.restoreDocumentButton.setOnClickListener {
            val documentId = preparedDocumentId
            if (documentId.isNullOrBlank()) {
                binding.statusText.text = getString(R.string.document_delete_requires_selection)
                return@setOnClickListener
            }
            val privateKey = privateKeyHex
            if (privateKey.isNullOrBlank()) {
                binding.statusText.text = getString(R.string.restore_requires_identity)
                return@setOnClickListener
            }

            workScheduler.enqueueRestore(documentId)
            selectDocument(store.readRecord(documentId), false)
            binding.statusText.text = getString(R.string.restore_queued, documentId)
        }
    }

    override fun onResume() {
        super.onResume()
        selectDocument(preparedDocumentId?.let { store.readRecord(it) } ?: store.latestDocument(), false)
    }

    private fun executeUpload(documentId: String, runningText: String) {
        binding.statusText.text = runningText
        val relays = currentRelays()
        session.saveRelays(relays)
        workScheduler.enqueuePendingSync(relays, documentId)
        selectDocument(store.readRecord(documentId), false)
        binding.statusText.text = getString(R.string.upload_queued, documentId)
    }

    private fun currentBlossomServers(): List<String> {
        return listOf(
                    binding.serverOneInput.text?.toString().orEmpty(),
                    binding.serverTwoInput.text?.toString().orEmpty(),
                    binding.serverThreeInput.text?.toString().orEmpty(),
                )
    }

    private fun currentRelays(): List<String> {
        return listOf(
                    binding.relayOneInput.text?.toString().orEmpty(),
                    binding.relayTwoInput.text?.toString().orEmpty(),
                    binding.relayThreeInput.text?.toString().orEmpty(),
                )
    }

    private fun bindDefaults() {
        val relays = session.loadRelays()
        binding.relayOneInput.setText(relays[0])
        binding.relayTwoInput.setText(relays[1])
        binding.relayThreeInput.setText(relays[2])
        val blossomServers = session.loadBlossomServers()
        binding.serverOneInput.setText(blossomServers[0])
        binding.serverTwoInput.setText(blossomServers[1])
        binding.serverThreeInput.setText(blossomServers[2])
    }

    private fun bindLatestDocument() {
        selectDocument(store.latestDocument(), false)
    }

    private fun updateActiveDocument(record: LocalDocumentRecord?) {
        val summary = record?.let { GarlandPlanInspector.summarize(store.readUploadPlan(it.documentId)) }
        val diagnostics = DocumentDiagnosticsFormatter.detailSections(record, summary)
        binding.activeDocumentText.text = if (record == null) {
            getString(R.string.active_document_none)
        } else {
            getString(R.string.active_document_loaded, record.displayName, record.uploadStatus)
        }
        binding.activeDocumentDiagnosticsText.text = diagnostics.overview
        bindDiagnosticSection(binding.activeDocumentUploadsLabel, binding.activeDocumentUploadsText, diagnostics.uploadsLabel, diagnostics.uploads)
        bindDiagnosticSection(binding.activeDocumentRelaysLabel, binding.activeDocumentRelaysText, diagnostics.relaysLabel, diagnostics.relays)
        binding.activeDocumentDetailText.text = if (record == null) {
            getString(R.string.active_document_details_none)
        } else {
            val localBytes = runCatching { store.contentFile(record.documentId).takeIf { it.exists() }?.length() ?: 0L }
                .getOrDefault(0L)
            val detailText = if (summary == null) {
                getString(R.string.active_document_details_none)
            } else {
                getString(
                    R.string.active_document_details,
                    summary.documentId.take(12),
                    summary.mimeType,
                    summary.sizeBytes,
                    summary.blockCount,
                    summary.serverCount,
                    summary.sha256Hex.take(12),
                )
            }
            val storageText = getString(
                R.string.active_document_storage,
                localBytes,
                if (summary == null) "missing" else "ready",
            )
            val serverText = summary?.servers
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(", ") { it.removePrefix("https://").removePrefix("wss://") }
                ?.let { getString(R.string.active_document_servers, it) }
                .orEmpty()
            val diagnosticText = record.lastSyncMessage?.takeIf { it.isNotBlank() }
                ?.let { getString(R.string.active_document_diagnostic, it) }
                .orEmpty()
            listOf(detailText, storageText, serverText, diagnosticText)
                .filter { it.isNotBlank() }
                .joinToString("\n")
        }
    }

    private fun selectDocument(record: LocalDocumentRecord?, announce: Boolean) {
        preparedDocumentId = record?.documentId
        updateActiveDocument(record)
        loadDocumentIntoInputs(record)
        refreshDocumentList(record?.documentId)
        if (announce && record != null) {
            binding.statusText.text = getString(R.string.document_selected, record.displayName, record.uploadStatus)
        }
    }

    private fun loadDocumentIntoInputs(record: LocalDocumentRecord?) {
        if (record == null) {
            clearDocumentInputs()
            return
        }

        binding.fileNameInput.setText(record.displayName)
        binding.mimeTypeInput.setText(record.mimeType)
        val content = runCatching { store.contentFile(record.documentId).readBytes().toString(Charsets.UTF_8) }
            .getOrElse { "" }
        binding.contentInput.setText(content)
    }

    private fun clearDocumentInputs() {
        binding.fileNameInput.setText("")
        binding.mimeTypeInput.setText("")
        binding.contentInput.setText("")
    }

    private fun bindDiagnosticSection(labelView: TextView, textView: TextView, label: String?, content: String?) {
        val visible = !content.isNullOrBlank()
        labelView.visibility = if (visible) View.VISIBLE else View.GONE
        textView.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            labelView.text = label
            textView.text = content
        }
    }

    private fun refreshDocumentList(selectedDocumentId: String?) {
        val records = store.listDocuments().sortedByDescending { it.updatedAt }
        binding.documentListContainer.removeAllViews()
        if (records.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = getString(R.string.document_list_empty)
            }
            binding.documentListContainer.addView(emptyView)
            return
        }

        records.forEach { record ->
            val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also { params ->
                    params.bottomMargin = (8 * resources.displayMetrics.density).toInt()
                }
                isAllCaps = false
                textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
                text = DocumentDiagnosticsFormatter.listLabel(
                    record = record,
                    summary = GarlandPlanInspector.summarize(store.readUploadPlan(record.documentId)),
                    isSelected = record.documentId == selectedDocumentId,
                )
                setOnClickListener { selectDocument(record, true) }
            }
            binding.documentListContainer.addView(button)
        }
    }
}
