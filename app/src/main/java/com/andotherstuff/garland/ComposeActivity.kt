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
import org.json.JSONObject
import kotlin.concurrent.thread

class ComposeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityComposeBinding
    private lateinit var session: GarlandSessionStore
    private lateinit var store: LocalDocumentStore
    private lateinit var uploadExecutor: GarlandUploadExecutor
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

        binding.cancelButton.setOnClickListener {
            if (!uploadInFlight) finish()
        }
        binding.saveButton.setOnClickListener { save() }
    }

    private fun save() {
        if (uploadInFlight) return

        val privateKey = session.loadPrivateKeyHex()
        if (privateKey.isNullOrBlank()) {
            showStatus(getString(R.string.compose_identity_required))
            startActivity(ConfigActivity.createIntent(this))
            return
        }

        val displayName = binding.fileNameInput.text?.toString().orEmpty().ifBlank { "note.txt" }
        val mimeType = GarlandConfig.ENCRYPTED_PAYLOAD_MIME_TYPE
        val content = binding.contentInput.text?.toString().orEmpty().toByteArray()
        showStatus(getString(R.string.compose_preparing_upload))

        val response = runCatching {
            val requestJson = GarlandConfig.buildPrepareWriteRequestJson(
                privateKeyHex = privateKey,
                displayName = displayName,
                mimeType = mimeType,
                content = content,
                blossomServers = session.resolvedBlossomServers(),
                createdAt = System.currentTimeMillis() / 1000,
            )
            JSONObject(NativeBridge.prepareSingleBlockWrite(requestJson))
        }.getOrElse { error ->
            showStatus(getString(R.string.compose_prepare_error, error.message ?: "unknown error"))
            return
        }

        if (!response.optBoolean("ok")) {
            showStatus(
                getString(
                    R.string.compose_prepare_error,
                    response.optString("error").ifBlank { "unknown error" },
                )
            )
            return
        }

        val plan = response.optJSONObject("plan")
        val documentId = plan?.optString("document_id").orEmpty()
        if (documentId.isBlank()) {
            showStatus(getString(R.string.compose_prepare_error, "missing document id"))
            return
        }

        store.upsertPreparedDocument(
            documentId = documentId,
            displayName = displayName,
            mimeType = mimeType,
            content = content,
            uploadPlanJson = response.toString(),
        )
        val relays = session.resolvedRelays()
        session.saveRelays(relays)
        setUploadInFlight(true)
        showStatus(getString(R.string.compose_uploading))
        thread(name = "garland-compose-upload") {
            val result = runCatching { uploadExecutor.executeDocumentUpload(documentId, relays) }
            runOnUiThread {
                setUploadInFlight(false)
                result.onSuccess { uploadResult ->
                    if (uploadResult.success) {
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        showStatus(getString(R.string.compose_upload_failure, uploadResult.message))
                    }
                }.onFailure { error ->
                    showStatus(getString(R.string.compose_upload_failure, error.message ?: "unknown error"))
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
