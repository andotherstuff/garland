package com.andotherstuff.garland

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
        binding.selectedDocumentText.text = state.selectedLabel
        binding.diagnosticsOverviewText.text = state.overview
        bindDiagnosticSection(binding.diagnosticsUploadsLabel, binding.diagnosticsUploadsText, state.uploadsLabel, state.uploads)
        bindDiagnosticSection(binding.diagnosticsRelaysLabel, binding.diagnosticsRelaysText, state.relaysLabel, state.relays)
        bindDiagnosticSection(binding.diagnosticsHistoryLabel, binding.diagnosticsHistoryText, state.historyLabel, state.history)
        renderDocumentOptions(state.documentOptions)
        binding.copyDiagnosticsButton.isEnabled = state.selectedDocumentId != null
        binding.copyDiagnosticsButton.tag = state.exportText
    }

    private fun copyDiagnosticsReport() {
        val report = binding.copyDiagnosticsButton.tag as? String ?: return
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Garland diagnostics", report))
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

    companion object {
        private const val EXTRA_DOCUMENT_ID = "document_id"

        fun createIntent(context: Context, documentId: String?): Intent {
            return Intent(context, DiagnosticsActivity::class.java).apply {
                documentId?.let { putExtra(EXTRA_DOCUMENT_ID, it) }
            }
        }
    }
}
