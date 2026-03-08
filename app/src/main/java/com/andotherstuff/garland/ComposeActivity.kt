package com.andotherstuff.garland

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.andotherstuff.garland.databinding.ActivityComposeBinding
import org.json.JSONObject

class ComposeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityComposeBinding
    private lateinit var session: GarlandSessionStore
    private lateinit var store: LocalDocumentStore
    private lateinit var workScheduler: GarlandWorkScheduler

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
        workScheduler = GarlandWorkScheduler(applicationContext)

        binding.saveButton.setOnClickListener { save() }
    }

    private fun save() {
        val privateKey = session.loadPrivateKeyHex()
        if (privateKey.isNullOrBlank()) {
            startActivity(ConfigActivity.createIntent(this))
            return
        }

        val displayName = binding.fileNameInput.text?.toString().orEmpty().ifBlank { "note.txt" }
        val content = binding.contentInput.text?.toString().orEmpty().toByteArray()

        val requestJson = GarlandConfig.buildPrepareWriteRequestJson(
            privateKeyHex = privateKey,
            content = content,
            blossomServers = session.resolvedBlossomServers(),
            createdAt = System.currentTimeMillis() / 1000,
        )

        val response = JSONObject(NativeBridge.prepareSingleBlockWrite(requestJson))
        if (response.optBoolean("ok")) {
            val plan = response.getJSONObject("plan")
            val documentId = plan.getString("document_id")
            store.upsertPreparedDocument(
                documentId = documentId,
                displayName = displayName,
                mimeType = "text/plain",
                content = content,
                uploadPlanJson = response.toString(),
            )
            val relays = session.resolvedRelays()
            session.saveRelays(relays)
            workScheduler.enqueuePendingSync(relays, documentId)
            setResult(RESULT_OK)
            finish()
        }
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, ComposeActivity::class.java)
    }
}
