package com.andotherstuff.garland

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.andotherstuff.garland.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var session: GarlandSessionStore
    private lateinit var store: LocalDocumentStore
    private lateinit var workScheduler: GarlandWorkScheduler
    private var selectedDocumentId: String? = null

    private val composeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            refreshSelectedDocumentState(store.latestDocument()?.documentId)
        }
    }

    private val configLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        bindDefaults()
        updateActiveDocument(selectedDocumentId?.let { store.readRecord(it) } ?: store.latestDocument())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = GarlandSessionStore(applicationContext)
        store = LocalDocumentStore(applicationContext)
        workScheduler = GarlandWorkScheduler(applicationContext)

        bindDefaults()
        binding.statusText.text = getString(R.string.app_boot_status)
        binding.createFileButton.setOnClickListener {
            composeLauncher.launch(ComposeActivity.createIntent(this))
        }

        binding.configButton.setOnClickListener {
            configLauncher.launch(ConfigActivity.createIntent(this))
        }

        binding.syncDocumentsButton.setOnClickListener {
            val relays = currentRelays()
            session.saveRelays(relays)
            workScheduler.enqueuePendingSync(relays)
            refreshDocumentList(selectedDocumentId)
            binding.statusText.text = getString(R.string.sync_documents_queued)
        }

        binding.openDiagnosticsButton.setOnClickListener {
            startActivity(DiagnosticsActivity.createIntent(this, selectedDocumentId ?: store.latestDocument()?.documentId))
        }

        binding.refreshDocumentsButton.setOnClickListener {
            refreshDocumentList(selectedDocumentId)
            updateActiveDocument(selectedDocumentId?.let { store.readRecord(it) } ?: store.latestDocument())
        }

        binding.retryUploadButton.setOnClickListener {
            val documentId = selectedDocumentId
            if (documentId.isNullOrBlank()) {
                binding.statusText.text = getString(R.string.upload_requires_prepared_document)
                return@setOnClickListener
            }

            executeUpload(documentId, getString(R.string.upload_retry_running, documentId))
        }

        binding.restoreDocumentButton.setOnClickListener {
            val documentId = selectedDocumentId
            if (documentId.isNullOrBlank()) {
                binding.statusText.text = getString(R.string.document_delete_requires_selection)
                return@setOnClickListener
            }
            val privateKey = session.loadPrivateKeyHex()
            if (privateKey.isNullOrBlank()) {
                binding.statusText.text = getString(R.string.restore_requires_identity)
                configLauncher.launch(ConfigActivity.createIntent(this))
                return@setOnClickListener
            }

            workScheduler.enqueueRestore(documentId, privateKey)
            selectDocument(store.readRecord(documentId), false)
            binding.statusText.text = getString(R.string.restore_queued, documentId)
        }

        binding.deleteDocumentButton.setOnClickListener {
            val document = selectedDocumentId?.let { store.readRecord(it) }
            if (document == null) {
                binding.statusText.text = getString(R.string.document_delete_requires_selection)
                return@setOnClickListener
            }

            store.deleteDocument(document.documentId)
            selectDocument(store.latestDocument(), false)
            binding.statusText.text = getString(R.string.document_deleted, document.displayName)
        }
    }

    override fun onResume() {
        super.onResume()
        selectDocument(selectedDocumentId?.let { store.readRecord(it) } ?: store.latestDocument(), false)
    }

    private fun executeUpload(documentId: String, runningText: String) {
        binding.statusText.text = runningText
        val relays = currentRelays()
        session.saveRelays(relays)
        workScheduler.enqueuePendingSync(relays, documentId)
        refreshSelectedDocumentState(documentId)
        binding.statusText.text = getString(R.string.upload_queued, documentId)
    }

    private fun refreshSelectedDocumentState(documentId: String?) {
        val record = documentId?.let { store.readRecord(it) } ?: store.latestDocument()
        selectedDocumentId = record?.documentId
        updateActiveDocument(record)
        refreshDocumentList(record?.documentId)
    }

    private fun currentBlossomServers(): List<String> {
        return GarlandConfig.normalizeConfiguredEndpoints(
            configured = listOf(
                binding.serverOneInput.text?.toString().orEmpty(),
                binding.serverTwoInput.text?.toString().orEmpty(),
                binding.serverThreeInput.text?.toString().orEmpty(),
            ),
            fallback = GarlandConfig.defaults.blossomServers,
        )
    }

    private fun currentRelays(): List<String> {
        return GarlandConfig.normalizeConfiguredEndpoints(
            configured = listOf(
                binding.relayOneInput.text?.toString().orEmpty(),
                binding.relayTwoInput.text?.toString().orEmpty(),
                binding.relayThreeInput.text?.toString().orEmpty(),
            ),
            fallback = GarlandConfig.defaults.relays,
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

    private fun updateActiveDocument(record: LocalDocumentRecord?) {
        bindMainStatus(record)
        val planDecode = record?.let { GarlandPlanInspector.decodeResult(store.readUploadPlan(it.documentId)) }
        val summary = planDecode?.summary
        val diagnostics = DocumentDiagnosticsFormatter.detailSections(record, summary, planMalformed = planDecode?.malformed == true)

        binding.activeDocumentText.text = if (record == null) {
            getString(R.string.active_document_none)
        } else {
            getString(
                R.string.active_document_loaded,
                record.displayName,
                DocumentDiagnosticsFormatter.statusLabel(record.uploadStatus),
            )
        }

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
                    summary.mimeType ?: record.mimeType,
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
            val serverText = currentBlossomServers()
                .takeIf { it.isNotEmpty() }
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

        binding.activeDocumentDiagnosticsText.text = diagnostics.overview
        renderProgressSection(
            binding.activeDocumentProgressLabel,
            binding.activeDocumentProgressContainer,
            diagnostics.progressLabel,
            diagnostics.progressSteps,
        )
        bindDiagnosticSection(binding.activeDocumentUploadsLabel, binding.activeDocumentUploadsText, diagnostics.uploadsLabel, diagnostics.uploads)
        bindDiagnosticSection(binding.activeDocumentRelaysLabel, binding.activeDocumentRelaysText, diagnostics.relaysLabel, diagnostics.relays)
    }

    private fun bindMainStatus(record: LocalDocumentRecord?) {
        val state = MainScreenStatusPresenter.build(record)
        binding.mainStatusChip.text = state.label
        binding.mainStatusHeadlineText.text = state.headline
        binding.mainStatusSummaryText.text = state.summary
        binding.mainNextStepsText.text = state.nextSteps.joinToString("\n") { "- $it" }

        val backgroundColor = ContextCompat.getColor(this, mainStatusBackgroundColor(state.tone))
        val textColor = ContextCompat.getColor(this, mainStatusTextColor(state.tone))
        binding.mainStatusChip.backgroundTintList = ColorStateList.valueOf(backgroundColor)
        binding.mainStatusChip.setTextColor(textColor)
    }

    private fun mainStatusBackgroundColor(tone: String): Int {
        return when (tone) {
            "success" -> R.color.garland_leaf
            "warning" -> R.color.garland_gold
            "danger" -> R.color.garland_error
            "active" -> R.color.garland_surface_alt
            else -> R.color.garland_surface_alt
        }
    }

    private fun mainStatusTextColor(tone: String): Int {
        return when (tone) {
            "success", "warning", "danger" -> R.color.garland_bg
            else -> R.color.garland_ink
        }
    }

    private fun selectDocument(record: LocalDocumentRecord?, announce: Boolean) {
        selectedDocumentId = record?.documentId
        updateActiveDocument(record)
        refreshDocumentList(record?.documentId)
        if (announce && record != null) {
            binding.statusText.text = getString(
                R.string.document_selected,
                record.displayName,
                DocumentDiagnosticsFormatter.statusLabel(record.uploadStatus),
            )
        }
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

    private fun renderProgressSection(
        labelView: TextView,
        container: LinearLayout,
        label: String?,
        steps: List<DocumentDiagnosticsFormatter.ProgressStep>,
    ) {
        val visible = steps.isNotEmpty()
        labelView.visibility = if (visible) View.VISIBLE else View.GONE
        container.visibility = if (visible) View.VISIBLE else View.GONE
        container.removeAllViews()
        if (!visible) return

        labelView.text = label
        steps.forEach { step ->
            container.addView(buildProgressRow(step))
        }
    }

    private fun buildProgressRow(step: DocumentDiagnosticsFormatter.ProgressStep): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).also { params ->
                params.bottomMargin = resources.getDimensionPixelSize(R.dimen.garland_tight_gap)
            }
        }
        val chip = TextView(this).apply {
            text = progressChipLabel(step.state)
            setTextAppearance(R.style.TextAppearance_Garland_StatusChip)
            setPaddingRelative(
                resources.getDimensionPixelSize(R.dimen.garland_status_chip_padding_horizontal),
                resources.getDimensionPixelSize(R.dimen.garland_status_chip_padding_vertical),
                resources.getDimensionPixelSize(R.dimen.garland_status_chip_padding_horizontal),
                resources.getDimensionPixelSize(R.dimen.garland_status_chip_padding_vertical),
            )
            background = ContextCompat.getDrawable(context, R.drawable.bg_status_chip)?.mutate()
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, progressChipBackgroundColor(step.state)))
            setTextColor(ContextCompat.getColor(context, progressChipTextColor(step.state)))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { params ->
                params.marginStart = resources.getDimensionPixelSize(R.dimen.garland_content_gap)
            }
        }
        val title = TextView(this).apply {
            text = step.label
            setTextAppearance(R.style.TextAppearance_Garland_BodyStrong)
        }
        val detail = TextView(this).apply {
            text = step.detail
            setTextAppearance(R.style.TextAppearance_Garland_BodySupport)
        }
        body.addView(title)
        body.addView(detail)
        row.addView(chip)
        row.addView(body)
        return row
    }

    private fun progressChipLabel(state: String): String {
        return when (state) {
            "done" -> "DONE"
            "active" -> "LIVE"
            "failed" -> "FAIL"
            else -> "WAIT"
        }
    }

    private fun progressChipBackgroundColor(state: String): Int {
        return when (state) {
            "done" -> R.color.garland_leaf
            "active" -> R.color.garland_gold
            "failed" -> R.color.garland_error
            else -> R.color.garland_surface_strong
        }
    }

    private fun progressChipTextColor(state: String): Int {
        return when (state) {
            "done", "active", "failed" -> R.color.garland_bg
            else -> R.color.garland_ink
        }
    }

    private fun refreshDocumentList(selectedDocumentId: String?) {
        val records = store.listDocuments().sortedByDescending { it.updatedAt }
        binding.documentListContainer.removeAllViews()
        if (records.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = getString(R.string.document_list_empty)
                setTextColor(ContextCompat.getColor(context, R.color.garland_muted))
            }
            binding.documentListContainer.addView(emptyView)
            return
        }

        records.forEach { record ->
            val planDecode = GarlandPlanInspector.decodeResult(store.readUploadPlan(record.documentId))
            val button = MaterialButton(
                android.view.ContextThemeWrapper(this, R.style.Widget_Garland_DocumentPickerButton),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also { params ->
                    params.bottomMargin = resources.getDimensionPixelSize(R.dimen.garland_list_item_gap)
                }
                isAllCaps = false
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPaddingRelative(
                    resources.getDimensionPixelSize(R.dimen.garland_card_padding),
                    resources.getDimensionPixelSize(R.dimen.garland_content_gap),
                    resources.getDimensionPixelSize(R.dimen.garland_card_padding),
                    resources.getDimensionPixelSize(R.dimen.garland_content_gap),
                )
                setLineSpacing(resources.getDimension(R.dimen.garland_tight_gap), 1f)
                text = DocumentDiagnosticsFormatter.listLabel(
                    record = record,
                    summary = planDecode.summary,
                    isSelected = record.documentId == selectedDocumentId,
                    planMalformed = planDecode.malformed,
                )
                styleDocumentButton(this, record.documentId == selectedDocumentId)
                setOnClickListener { selectDocument(record, true) }
            }
            binding.documentListContainer.addView(button)
        }
    }

    private fun styleDocumentButton(button: MaterialButton, isSelected: Boolean) {
        val backgroundColor = ContextCompat.getColor(this, if (isSelected) R.color.garland_surface_raised else R.color.garland_surface_soft)
        val strokeColor = ContextCompat.getColor(this, if (isSelected) R.color.garland_leaf else R.color.garland_outline_soft)
        val textColor = ContextCompat.getColor(this, if (isSelected) R.color.garland_ink else R.color.garland_muted)
        button.backgroundTintList = ColorStateList.valueOf(backgroundColor)
        button.strokeColor = ColorStateList.valueOf(strokeColor)
        button.setTextColor(textColor)
        button.cornerRadius = resources.getDimensionPixelSize(R.dimen.garland_button_corner_radius)
        button.strokeWidth = if (isSelected) {
            (2 * resources.displayMetrics.density).toInt()
        } else {
            resources.displayMetrics.density.toInt().coerceAtLeast(1)
        }
    }
}
