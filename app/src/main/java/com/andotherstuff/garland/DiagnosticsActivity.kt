package com.andotherstuff.garland

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.andotherstuff.garland.databinding.ActivityDiagnosticsBinding
import com.google.android.material.button.MaterialButton

class DiagnosticsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDiagnosticsBinding
    private lateinit var store: LocalDocumentStore
    private var selectedDocumentId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val v = resources.getDimensionPixelSize(R.dimen.garland_screen_padding)
            val side = resources.getDimensionPixelSize(R.dimen.garland_home_side_padding)
            view.setPadding(bars.left + side, bars.top + v, bars.right + side, bars.bottom + v)
            insets
        }
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
        bindStatusNarrative(state)
        binding.selectedDocumentText.text = state.selectedLabel
        binding.documentIdText.text = state.documentIdLabel
        binding.documentIdText.visibility = if (state.documentIdLabel.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.diagnosticsFailureFocusTitleText.text = state.failureFocusTitle
        binding.diagnosticsFailureFocusSummaryText.text = state.failureFocusSummary
        binding.diagnosticsOverviewText.text = state.overview
        binding.diagnosticsNextStepsText.text = state.nextSteps.joinToString("\n") { "- $it" }
        renderProgressSection(binding.diagnosticsPipelineContainer, state.progressSteps)
        bindDiagnosticSection(binding.diagnosticsUploadsLabel, binding.diagnosticsUploadsText, state.uploadsLabel, state.uploads)
        bindDiagnosticSection(binding.diagnosticsRelaysLabel, binding.diagnosticsRelaysText, state.relaysLabel, state.relays)
        bindDiagnosticSection(binding.diagnosticsHistoryLabel, binding.diagnosticsHistoryText, state.historyLabel, state.history)
        bindDiagnosticSection(
            binding.diagnosticsTroubleshootingLabel,
            binding.diagnosticsTroubleshootingText,
            state.troubleshootingLabel,
            state.troubleshootingItems.takeIf { it.isNotEmpty() }?.joinToString("\n") { "- $it" },
        )
        bindText(binding.diagnosticsTroubleshootingSummaryText, state.troubleshootingSummary)
        bindText(binding.diagnosticsEvidenceHintText, state.evidenceHint)
        binding.diagnosticsAgentReportHintText.text = state.reportHint
        binding.diagnosticsAgentReportText.text = state.reportPreview
        renderDocumentOptions(state.documentOptions)
        binding.copyDiagnosticsButton.isEnabled = state.selectedDocumentId != null
        binding.copyDiagnosticsButton.tag = state.exportText
        binding.copyDocumentIdButton.isEnabled = state.selectedDocumentId != null
    }

    private fun bindStatusNarrative(state: DocumentDiagnosticsScreenState) {
        binding.diagnosticsStatusChip.text = state.statusLabel
        binding.diagnosticsHeadlineText.text = state.statusHeadline
        binding.diagnosticsSummaryText.text = state.statusSummary

        val backgroundColor = ContextCompat.getColor(this, statusBackgroundColor(state.statusTone))
        val textColor = ContextCompat.getColor(this, statusTextColor(state.statusTone))
        binding.diagnosticsStatusChip.backgroundTintList = ColorStateList.valueOf(backgroundColor)
        binding.diagnosticsStatusChip.setTextColor(textColor)
    }

    private fun statusBackgroundColor(tone: String): Int {
        return when (tone) {
            "success" -> R.color.garland_leaf
            "warning" -> R.color.garland_gold
            "danger" -> R.color.garland_error
            "active" -> R.color.garland_surface_alt
            else -> R.color.garland_surface_alt
        }
    }

    private fun statusTextColor(tone: String): Int {
        return when (tone) {
            "success", "warning", "danger" -> R.color.garland_ink
            else -> R.color.garland_ink
        }
    }

    private fun copyDiagnosticsReport() {
        val report = binding.copyDiagnosticsButton.tag as? String ?: return
        copyText(getString(R.string.diagnostics_clipboard_label), report)
    }

    private fun copySelectedDocumentId() {
        val documentId = selectedDocumentId ?: return
        copyText(getString(R.string.document_id_clipboard_label), documentId)
    }

    private fun renderDocumentOptions(options: List<DocumentDiagnosticsOption>) {
        binding.diagnosticsDocumentListContainer.removeAllViews()
        if (options.isEmpty()) {
            binding.diagnosticsDocumentListContainer.addView(TextView(this).apply {
                text = getString(R.string.document_list_empty)
                setTextColor(ContextCompat.getColor(context, R.color.garland_muted))
            })
            return
        }

        options.forEach { option ->
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
                text = "${option.label}\n${option.supportingText}"
                setPaddingRelative(
                    resources.getDimensionPixelSize(R.dimen.garland_card_padding),
                    resources.getDimensionPixelSize(R.dimen.garland_content_gap),
                    resources.getDimensionPixelSize(R.dimen.garland_card_padding),
                    resources.getDimensionPixelSize(R.dimen.garland_content_gap),
                )
                setLineSpacing(resources.getDimension(R.dimen.garland_tight_gap), 1f)
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                styleDocumentOptionButton(this, option.selected)
                setOnClickListener {
                    if (!option.selected) {
                        selectedDocumentId = option.documentId
                        render()
                    }
                }
            }
            binding.diagnosticsDocumentListContainer.addView(button)
        }
    }

    private fun styleDocumentOptionButton(button: MaterialButton, isSelected: Boolean) {
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

    private fun bindDiagnosticSection(labelView: TextView, textView: TextView, label: String?, content: String?) {
        val visible = !content.isNullOrBlank()
        labelView.visibility = if (visible) View.VISIBLE else View.GONE
        textView.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            labelView.text = label
            textView.text = content
        }
    }

    private fun bindText(textView: TextView, content: String?) {
        val visible = !content.isNullOrBlank()
        textView.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            textView.text = content
        }
    }

    private fun renderProgressSection(
        container: LinearLayout,
        steps: List<DocumentDiagnosticsFormatter.ProgressStep>,
    ) {
        container.visibility = if (steps.isEmpty()) View.GONE else View.VISIBLE
        container.removeAllViews()
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
            "done" -> getString(R.string.progress_chip_done)
            "active" -> getString(R.string.progress_chip_live)
            "failed" -> getString(R.string.progress_chip_fail)
            else -> getString(R.string.progress_chip_wait)
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
            "done", "active", "failed" -> R.color.garland_ink
            else -> R.color.garland_ink
        }
    }

    private fun copyText(label: String, text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, getString(R.string.clipboard_copied_toast, label), Toast.LENGTH_SHORT).show()
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
