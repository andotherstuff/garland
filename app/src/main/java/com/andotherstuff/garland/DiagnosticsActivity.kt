package com.andotherstuff.garland

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.Toast
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.andotherstuff.garland.databinding.ActivityDiagnosticsBinding
import com.google.android.material.button.MaterialButton

class DiagnosticsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDiagnosticsBinding
    private lateinit var store: LocalDocumentStore
    private var selectedDocumentId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = LocalDocumentStore(applicationContext)
        selectedDocumentId = intent.getStringExtra(EXTRA_DOCUMENT_ID)

        binding.refreshDiagnosticsButton.setOnClickListener { render() }
        binding.copyDiagnosticsButton.setOnClickListener { copyDiagnosticsReport() }
        binding.copyDocumentIdButton.setOnClickListener { copySelectedDocumentId() }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val state = DocumentDiagnosticsScreenPresenter.build(
            records = store.listDocuments(),
            selectedDocumentId = selectedDocumentId,
            readUploadPlan = store::readUploadPlan,
        )
        selectedDocumentId = state.selectedDocumentId
        title = state.title
        bindHeadline(state.headlineTone, state.headline, state.summary)
        binding.selectedDocumentText.text = state.selectedLabel
        binding.documentIdText.text = state.documentIdLabel
        binding.documentIdText.visibility = if (state.documentIdLabel.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.diagnosticsOverviewText.text = state.overview
        bindDiagnosticSection(binding.diagnosticsUploadsLabel, binding.diagnosticsUploadsText, state.uploadsLabel, state.uploads)
        bindDiagnosticSection(binding.diagnosticsRelaysLabel, binding.diagnosticsRelaysText, state.relaysLabel, state.relays)
        bindDiagnosticSection(binding.diagnosticsHistoryLabel, binding.diagnosticsHistoryText, state.historyLabel, state.history)
        bindDiagnosticSection(
            binding.diagnosticsTroubleshootingLabel,
            binding.diagnosticsTroubleshootingText,
            state.troubleshootingLabel,
            state.troubleshootingItems.takeIf { it.isNotEmpty() }?.joinToString("\n") { "- $it" },
        )
        renderDocumentOptions(state.documentOptions)
        binding.copyDiagnosticsButton.isEnabled = state.selectedDocumentId != null
        binding.copyDiagnosticsButton.tag = state.exportText
        binding.copyDocumentIdButton.isEnabled = state.selectedDocumentId != null
    }

    private fun bindHeadline(tone: String, headline: String, summary: String) {
        val (label, fillColor, textColor) = when (tone) {
            "error" -> Triple("Error", R.color.garland_error, R.color.garland_bg)
            "warning" -> Triple("Watch", R.color.garland_warning, R.color.garland_bg)
            "success" -> Triple("Ready", R.color.garland_leaf, R.color.garland_bg)
            "neutral" -> Triple("Idle", R.color.garland_outline, R.color.garland_ink)
            else -> Triple("Info", R.color.garland_info, R.color.garland_bg)
        }
        binding.diagnosticsHeadlineToneText.text = label
        binding.diagnosticsHeadlineToneText.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, fillColor))
        binding.diagnosticsHeadlineToneText.setTextColor(ContextCompat.getColor(this, textColor))
        binding.diagnosticsHeadlineText.text = headline
        binding.diagnosticsSummaryText.text = summary
    }

    private fun copyDiagnosticsReport() {
        val report = binding.copyDiagnosticsButton.tag as? String ?: return
        copyText("Garland diagnostics", report)
    }

    private fun copySelectedDocumentId() {
        val documentId = selectedDocumentId ?: return
        copyText("Garland document ID", documentId)
    }

    private fun renderDocumentOptions(options: List<DocumentDiagnosticsOption>) {
        binding.diagnosticsDocumentListContainer.removeAllViews()
        if (options.isEmpty()) {
            binding.diagnosticsDocumentListContainer.addView(TextView(this).apply {
                text = getString(R.string.document_list_empty)
            })
            return
        }

        options.forEach { option ->
            val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also { params ->
                    params.bottomMargin = (8 * resources.displayMetrics.density).toInt()
                }
                isAllCaps = false
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                text = option.label
                isEnabled = !option.selected
                styleDocumentButton(this, option.selected)
                setOnClickListener {
                    selectedDocumentId = option.documentId
                    render()
                }
            }
            binding.diagnosticsDocumentListContainer.addView(button)
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

    private fun styleDocumentButton(button: MaterialButton, isSelected: Boolean) {
        val backgroundColor = ContextCompat.getColor(this, if (isSelected) R.color.garland_surface_high else R.color.garland_surface_alt)
        val strokeColor = ContextCompat.getColor(this, if (isSelected) R.color.garland_info else R.color.garland_outline)
        val textColor = ContextCompat.getColor(this, if (isSelected) R.color.garland_ink else R.color.garland_muted)
        button.backgroundTintList = ColorStateList.valueOf(backgroundColor)
        button.strokeColor = ColorStateList.valueOf(strokeColor)
        button.setTextColor(textColor)
        button.strokeWidth = if (isSelected) {
            (2 * resources.displayMetrics.density).toInt()
        } else {
            resources.displayMetrics.density.toInt().coerceAtLeast(1)
        }
    }

    private fun copyText(label: String, text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EXTRA_DOCUMENT_ID = "document_id"

        fun createIntent(context: Context, documentId: String?): Intent {
            return Intent(context, DiagnosticsActivity::class.java).apply {
                documentId?.let { putExtra(EXTRA_DOCUMENT_ID, it) }
            }
        }
    }
}
