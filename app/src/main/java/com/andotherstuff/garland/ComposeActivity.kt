package com.andotherstuff.garland

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.andotherstuff.garland.databinding.ActivityComposeBinding
import kotlin.concurrent.thread

class ComposeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityComposeBinding
    private lateinit var session: GarlandSessionStore
    private lateinit var store: LocalDocumentStore
    private lateinit var uploadExecutor: GarlandUploadExecutor
    private lateinit var uploadWorkflow: ComposeUploadWorkflow
    private var uploadInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityComposeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val v = resources.getDimensionPixelSize(R.dimen.garland_screen_padding)
            val side = resources.getDimensionPixelSize(R.dimen.garland_home_side_padding)
            binding.contentLayout.setPadding(bars.left + side, bars.top + v, bars.right + side, 0)
            binding.bottomBar.setPadding(bars.left + side, 0, bars.right + side, bars.bottom + v + resources.getDimensionPixelSize(R.dimen.garland_section_gap))
            binding.bottomBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
                val barHeight = bottom - top
                binding.contentLayout.setPadding(
                    bars.left + side,
                    bars.top + v,
                    bars.right + side,
                    barHeight,
                )
            }
            insets
        }

        session = GarlandSessionStore(applicationContext)
        store = LocalDocumentStore(applicationContext)
        uploadExecutor = GarlandUploadExecutor(applicationContext)
        uploadWorkflow = ComposeUploadWorkflow(
            loadPrivateKeyHex = session::loadPrivateKeyHex,
            resolveBlossomServers = session::resolvedBlossomServers,
            resolveRelays = session::resolvedRelays,
            saveRelays = session::saveRelays,
            prepareSingleBlockWrite = NativeBridge::prepareSingleBlockWrite,
            upsertPreparedDocument = { documentId, displayName, mimeType, content, uploadPlanJson ->
                store.upsertPreparedDocument(
                    documentId = documentId,
                    displayName = displayName,
                    mimeType = mimeType,
                    content = content,
                    uploadPlanJson = uploadPlanJson,
                )
            },
            executeDocumentUpload = uploadExecutor::executeDocumentUpload,
        )

        binding.cancelButton.setOnClickListener {
            if (!uploadInFlight) finish()
        }
        binding.saveButton.setOnClickListener { save() }
    }

    private fun save() {
        if (uploadInFlight) return

        val displayName = binding.fileNameInput.text?.toString().orEmpty().ifBlank { "note.txt" }
        val content = binding.contentInput.text?.toString().orEmpty().toByteArray()
        setUploadInFlight(true)
        thread(name = "garland-compose-upload") {
            val result = uploadWorkflow.submit(displayName, content) { stage ->
                runOnUiThread {
                    when (stage) {
                        ComposeUploadStage.PREPARING -> showStatus(getString(R.string.compose_preparing_upload))
                        ComposeUploadStage.UPLOADING -> showStatus(getString(R.string.compose_uploading))
                    }
                }
            }
            runOnUiThread {
                setUploadInFlight(false)
                when (result) {
                    is ComposeUploadResult.Success -> {
                        setResult(RESULT_OK)
                        finish()
                    }
                    is ComposeUploadResult.RequiresIdentity -> {
                        showStatus(result.message)
                        startActivity(ConfigActivity.createIntent(this))
                    }
                    is ComposeUploadResult.Failure -> {
                        showStatus(result.message)
                    }
                }
            }
        }
    }

    private fun setUploadInFlight(inFlight: Boolean) {
        uploadInFlight = inFlight
        binding.saveButton.isEnabled = !inFlight
        binding.cancelButton.isEnabled = !inFlight
    }

    private fun showStatus(message: String) {
        binding.composeStatusText.text = message
        binding.composeStatusText.visibility = View.VISIBLE
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, ComposeActivity::class.java)
    }
}
