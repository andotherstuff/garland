package com.andotherstuff.garland

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.andotherstuff.garland.databinding.ActivityConfigBinding
import org.json.JSONObject

class ConfigActivity : AppCompatActivity() {
    private lateinit var binding: ActivityConfigBinding
    private lateinit var session: GarlandSessionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val v = resources.getDimensionPixelSize(R.dimen.garland_screen_padding)
            val side = resources.getDimensionPixelSize(R.dimen.garland_home_side_padding)
            binding.contentLayout.setPadding(bars.left + side, bars.top + v, bars.right + side, 0)
            binding.bottomBar.setPadding(bars.left + side, 0, bars.right + side, bars.bottom + v + resources.getDimensionPixelSize(R.dimen.garland_section_gap))
            insets
        }

        session = GarlandSessionStore(applicationContext)
        bindDefaults()

        binding.cancelButton.setOnClickListener { finish() }

        binding.generateIdentityButton.setOnClickListener {
            val passphrase = binding.passphraseInput.text?.toString().orEmpty()
            val response = JSONObject(NativeBridge.generateIdentity(passphrase))
            handleIdentityResponse(response, response.optString("mnemonic"))
        }

        binding.loadIdentityButton.setOnClickListener {
            val mnemonic = binding.mnemonicInput.text?.toString().orEmpty()
            val passphrase = binding.passphraseInput.text?.toString().orEmpty()
            val response = JSONObject(NativeBridge.deriveIdentity(mnemonic, passphrase))
            handleIdentityResponse(response)
        }

        binding.deriveButton.setOnClickListener {
            session.saveRelays(currentRelays())
            session.saveBlossomServers(currentBlossomServers())
            setResult(RESULT_OK)
            finish()
        }
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

        val savedKey = session.loadPrivateKeyHex()
        binding.passphraseInput.setText(session.loadPassphrase())
        if (!savedKey.isNullOrBlank()) {
            binding.identityStatusText.text = getString(R.string.identity_already_loaded)
            binding.identityStatusText.visibility = View.VISIBLE
        }
    }

    private fun handleIdentityResponse(response: JSONObject, generatedMnemonic: String? = null) {
        val passphrase = binding.passphraseInput.text?.toString().orEmpty()
        val statusText = if (response.optBoolean("ok")) {
            val derivedPrivateKey = GarlandSessionStore.normalizePrivateKeyHex(response.optString("private_key_hex"))
            if (derivedPrivateKey != null) {
                session.savePrivateKeyHex(derivedPrivateKey)
                session.savePassphrase(passphrase)
                val mnemonic = generatedMnemonic?.trim().orEmpty()
                if (mnemonic.isNotEmpty()) {
                    binding.mnemonicInput.setText(mnemonic)
                    getString(R.string.identity_generated, mnemonic, response.optString("nsec"))
                } else {
                    getString(R.string.identity_loaded, response.optString("nsec"))
                }
            } else {
                getString(R.string.identity_error, "missing private key")
            }
        } else {
            getString(R.string.identity_error, response.optString("error"))
        }
        binding.identityStatusText.text = statusText
        binding.identityStatusText.visibility = View.VISIBLE
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

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, ConfigActivity::class.java)
    }
}
