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
        updateActiveDocument(selectedDocumentId?.let { store.readRecord(it) } ?: store.latestDocument())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = GarlandSessionStore(applicationContext)
        store = LocalDocumentStore(applicationContext)
        workScheduler = GarlandWorkScheduler(applicationContext)

        binding.statusText.text = getString(R.string.app_boot_status)
        binding.createFileButton.setOnClickListener {
            composeLauncher.launch(ComposeActivity.createIntent(this))
        }

        binding.configButton.setOnClickListener {
            configLauncher.launch(ConfigActivity.createIntent(this))
        }

        binding.retryUploadButton.setOnClickListener {
            val documentId = selectedDocumentId
            if (documentId.isNullOrBlank()) {
                binding.statusText.text = getString(R.string.upload_requires_prepared_document)
                return@setOnClickListener
            }

            executeUpload(documentId, getString(R.string.upload_retry_running, documentId))
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

    private fun currentRelays(): List<String> {
        return session.loadRelays()
    }

    private fun updateActiveDocument(record: LocalDocumentRecord?) {
        val planDecode = record?.let { GarlandPlanInspector.decodeResult(store.readUploadPlan(it.documentId)) }
        val summary = planDecode?.summary
        val selectedNoteState = MainScreenSelectedNotePresenter.build(record, summary)
        val actionState = MainScreenActionPresenter.build(record, summary)

        bindMainStatus(record)

        binding.activeDocumentText.text = selectedNoteState.title
        binding.activeDocumentDetailText.text = selectedNoteState.detail
        binding.retryUploadButton.visibility = if (actionState.primaryVisible) View.VISIBLE else View.GONE
        binding.retryUploadButton.text = actionState.primaryLabel
        binding.deleteDocumentButton.visibility = if (actionState.deleteVisible) View.VISIBLE else View.GONE
    }

    private fun bindMainStatus(record: LocalDocumentRecord?) {
        val state = MainScreenStatusPresenter.build(record, !session.loadPrivateKeyHex().isNullOrBlank())
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
            "success", "warning", "danger" -> R.color.garland_ink
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
                text = MainScreenSelectedNotePresenter.listLabel(
                    record = record,
                    summary = planDecode.summary,
                    isSelected = record.documentId == selectedDocumentId,
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
