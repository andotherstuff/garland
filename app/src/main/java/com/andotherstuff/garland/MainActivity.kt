package com.andotherstuff.garland

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.andotherstuff.garland.databinding.ActivityMainBinding
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var session: GarlandSessionStore
    private lateinit var store: LocalDocumentStore
    private lateinit var uploadExecutor: GarlandUploadExecutor
    private var privateKeyHex: String? = null
    private var preparedDocumentId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        session = GarlandSessionStore(applicationContext)
        store = LocalDocumentStore(applicationContext)
        uploadExecutor = GarlandUploadExecutor(applicationContext)

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
                preparedDocumentId = documentId
                updateActiveDocument(store.readRecord(documentId))
                getString(
                    R.string.upload_prepared,
                    documentId,
                    plan.getJSONArray("uploads").length()
                )
            } else {
                preparedDocumentId = null
                updateActiveDocument(null)
                getString(R.string.upload_prepare_error, response.optString("error"))
            }
        }

        binding.executeUploadButton.setOnClickListener {
            val documentId = preparedDocumentId
            if (documentId.isNullOrBlank()) {
                binding.statusText.text = getString(R.string.upload_requires_prepared_document)
                return@setOnClickListener
            }

            binding.statusText.text = getString(R.string.upload_running, documentId)
            val relays = currentRelays()
            session.saveRelays(relays)
            thread {
                val result = runCatching { uploadExecutor.executeDocumentUpload(documentId, relays) }
                runOnUiThread {
                    binding.statusText.text = result.fold(
                        onSuccess = {
                            updateActiveDocument(store.readRecord(documentId))
                            getString(
                                R.string.upload_result,
                                it.uploadedShares,
                                it.attemptedShares,
                                it.message
                            )
                        },
                        onFailure = {
                            updateActiveDocument(store.readRecord(documentId))
                            getString(R.string.upload_execution_error, it.message ?: "unknown error")
                        }
                    )
                }
            }
        }
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
        val latest = store.latestDocument()
        preparedDocumentId = latest?.documentId
        updateActiveDocument(latest)
    }

    private fun updateActiveDocument(record: LocalDocumentRecord?) {
        binding.activeDocumentText.text = if (record == null) {
            getString(R.string.active_document_none)
        } else {
            getString(R.string.active_document_loaded, record.displayName, record.uploadStatus)
        }
    }
}
