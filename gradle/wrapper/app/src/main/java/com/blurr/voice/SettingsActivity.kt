package com.blurr.voice

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.NumberPicker
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.blurr.voice.api.GoogleTts
import com.blurr.voice.api.PicovoiceKeyManager
import com.blurr.voice.api.TTSVoice
import com.blurr.voice.utilities.SpeechCoordinator
import com.blurr.voice.utilities.VoicePreferenceManager
import com.blurr.voice.utilities.UserProfileManager
import com.blurr.voice.utilities.WakeWordManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class SettingsActivity : BaseNavigationActivity() {

    private lateinit var spinnerLlmProvider: android.widget.Spinner
    private lateinit var editApiKey: android.widget.EditText
    private lateinit var editLlmModel: android.widget.EditText
    private lateinit var btnSaveApiKey: Button
    private lateinit var btnTestConnection: Button
    private lateinit var textFallbackChain: TextView

    private lateinit var switchShowThoughts: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var permissionsInfoButton: TextView
    private lateinit var batteryOptimizationHelpButton: TextView
    private lateinit var appVersionText: TextView
    private lateinit var editUserName: android.widget.EditText
    private lateinit var editUserEmail: android.widget.EditText
    private lateinit var editWakeWordKey: android.widget.EditText
    private lateinit var textGetPicovoiceKeyLink: TextView
    private lateinit var wakeWordButton: TextView
    private lateinit var buttonSignOut: Button
    private lateinit var wakeWordManager: WakeWordManager
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

    private lateinit var sc: SpeechCoordinator
    private lateinit var sharedPreferences: SharedPreferences

    companion object {
        private const val PREFS_NAME = "BlurrSettings"
        const val KEY_SHOW_THOUGHTS = "show_thoughts"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Initialize permission launcher first
        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Toast.makeText(this, "Permission granted!", Toast.LENGTH_SHORT).show()
                // The manager will handle the service start after permission is granted.
                wakeWordManager.handleWakeWordButtonClick(wakeWordButton)
                updateWakeWordButtonState()
            } else {
                Toast.makeText(this, "Permission denied.", Toast.LENGTH_SHORT).show()
            }
        }

        initialize()
        setupUI()
        loadAllSettings()
        setupAutoSavingListeners()
        setupLLMConfig()
    }

    override fun onStop() {
        super.onStop()
        sc.stop()
    }

    private fun initialize() {
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sc = SpeechCoordinator.getInstance(this)
        wakeWordManager = WakeWordManager(this, requestPermissionLauncher)
    }

    private fun setupUI() {
        spinnerLlmProvider = findViewById(R.id.spinner_llm_provider)
        editApiKey = findViewById(R.id.edit_api_key)
        editLlmModel = findViewById(R.id.edit_llm_model)
        btnSaveApiKey = findViewById(R.id.btn_save_api_key)
        btnTestConnection = findViewById(R.id.btn_test_connection)
        textFallbackChain = findViewById(R.id.text_fallback_chain)

        switchShowThoughts = findViewById(R.id.switchShowThoughts)
        permissionsInfoButton = findViewById(R.id.permissionsInfoButton)
        appVersionText = findViewById(R.id.appVersionText)
        batteryOptimizationHelpButton = findViewById(R.id.batteryOptimizationHelpButton)
      
        editWakeWordKey = findViewById(R.id.editWakeWordKey)
        wakeWordButton = findViewById(R.id.wakeWordButton)

        buttonSignOut = findViewById(R.id.buttonSignOut)

        editUserName = findViewById(R.id.editUserName)
        editUserEmail = findViewById(R.id.editUserEmail)
        textGetPicovoiceKeyLink = findViewById(R.id.textGetPicovoiceKeyLink)

        setupClickListeners()

        // Prefill profile fields from saved values
        kotlin.runCatching {
            val pm = UserProfileManager(this)
            editUserName.setText(pm.getName() ?: "")
            editUserEmail.setText(pm.getEmail() ?: "")
        }

        // Show app version
        val versionName = BuildConfig.VERSION_NAME
        appVersionText.text = "Version $versionName"
    }

    private fun setupClickListeners() {
        permissionsInfoButton.setOnClickListener {
            val intent = Intent(this, PermissionsActivity::class.java)
            startActivity(intent)
        }
        batteryOptimizationHelpButton.setOnClickListener {
            showBatteryOptimizationDialog()
        }
        wakeWordButton.setOnClickListener {
            val keyManager = PicovoiceKeyManager(this)
            
            // Step 1: Save key if provided in the EditText
            val userKey = editWakeWordKey.text.toString().trim()
            if (userKey.isNotEmpty()) {
                keyManager.saveUserProvidedKey(userKey)
                Toast.makeText(this, "Wake word key saved.", Toast.LENGTH_SHORT).show()
            }
            
            // Step 2: Check if we have a key (either just saved or previously saved)
            val hasKey = !keyManager.getUserProvidedKey().isNullOrBlank()
            
            if (!hasKey) {
                showPicovoiceKeyRequiredDialog()
                return@setOnClickListener
            }
            
            // Step 3: Enable the wake word
            wakeWordManager.handleWakeWordButtonClick(wakeWordButton)
            // Give the service a moment to update its state before refreshing the UI
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ updateWakeWordButtonState() }, 500)
        }
        textGetPicovoiceKeyLink.setOnClickListener {
            val url = "https://console.picovoice.ai/login"
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(url)
            try {
                startActivity(intent)
            } catch (e: Exception) {
                // This might happen if the device has no web browser
                Toast.makeText(this, "Could not open link. No browser found.", Toast.LENGTH_SHORT).show()
                Log.e("SettingsActivity", "Failed to open Picovoice link", e)
            }
        }


        buttonSignOut.setOnClickListener {
            showSignOutConfirmationDialog()
        }

        findViewById<TextView>(R.id.viewTaskLogsButton).setOnClickListener {
            startActivity(Intent(this, TaskLogsListActivity::class.java))
        }
    }

    private fun setupAutoSavingListeners() {
        switchShowThoughts.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(KEY_SHOW_THOUGHTS, isChecked).apply()
        }
    }

    private fun loadAllSettings() {
        val keyManager = PicovoiceKeyManager(this)
        editWakeWordKey.setText(keyManager.getUserProvidedKey() ?: "") 
        
        // Update wake word button state
        updateWakeWordButtonState()

        switchShowThoughts.isChecked = sharedPreferences.getBoolean(KEY_SHOW_THOUGHTS, false)
    }

    private fun setupLLMConfig() {
        val providers = arrayOf("Gemini", "OpenAI", "Grok", "OpenRouter", "Pollinations")
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, providers)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLlmProvider.adapter = adapter

        // Set listener on provider select to load key & model
        spinnerLlmProvider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val provider = providers[position]
                val key = com.blurr.voice.api.UniversalLLMClient.getSavedKey(this@SettingsActivity, provider)
                val model = com.blurr.voice.api.UniversalLLMClient.getSelectedModel(this@SettingsActivity, provider)
                
                editApiKey.setText(key)
                editLlmModel.setText(model)
                
                // Update active fallback preferred provider
                com.blurr.voice.api.UniversalLLMClient.setPreferredProvider(this@SettingsActivity, provider)
                updateFallbackChainDisplay()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Save button click
        btnSaveApiKey.setOnClickListener {
            val provider = spinnerLlmProvider.selectedItem.toString()
            val key = editApiKey.text.toString().trim()
            val model = editLlmModel.text.toString().trim()
            
            com.blurr.voice.api.UniversalLLMClient.saveKey(this, provider, key)
            com.blurr.voice.api.UniversalLLMClient.saveSelectedModel(this, provider, model)
            
            Toast.makeText(this, "$provider saved successfully!", Toast.LENGTH_SHORT).show()
            updateFallbackChainDisplay()
        }

        // Test API / Connection & Model selector
        btnTestConnection.setOnClickListener {
            val provider = spinnerLlmProvider.selectedItem.toString()
            val key = editApiKey.text.toString().trim()
            if (key.isBlank() && 
                !provider.equals("Pollinations", ignoreCase = true) && 
                !provider.equals("Ollama", ignoreCase = true)) {
                Toast.makeText(this, "Please enter an API Key first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            Toast.makeText(this, "Testing connection & fetching models for $provider...", Toast.LENGTH_SHORT).show()
            
            lifecycleScope.launch {
                try {
                    val models = com.blurr.voice.api.UniversalLLMClient.testAndFetchModels(provider, key)
                    if (models.isNotEmpty()) {
                        val modelsArray = models.toTypedArray()
                        AlertDialog.Builder(this@SettingsActivity)
                            .setTitle("Select Model ($provider)")
                            .setItems(modelsArray) { _, index ->
                                val selectedModel = modelsArray[index]
                                editLlmModel.setText(selectedModel)
                                com.blurr.voice.api.UniversalLLMClient.saveSelectedModel(this@SettingsActivity, provider, selectedModel)
                                Toast.makeText(this@SettingsActivity, "$selectedModel selected!", Toast.LENGTH_SHORT).show()
                            }
                            .show()
                    } else {
                        Toast.makeText(this@SettingsActivity, "Connection Failed or invalid key!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@SettingsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        updateFallbackChainDisplay()
    }

    private fun updateFallbackChainDisplay() {
        val primary = com.blurr.voice.api.UniversalLLMClient.getPreferredProvider(this)
        val all = listOf("Gemini", "OpenAI", "Grok", "OpenRouter")
        
        // Active providers with non-empty key
        val activeList = mutableListOf<String>()
        val primaryKey = com.blurr.voice.api.UniversalLLMClient.getSavedKey(this, primary)
        if (primaryKey.isNotEmpty()) {
            activeList.add(primary)
        }
        all.forEach { provider ->
            if (provider != primary) {
                val key = com.blurr.voice.api.UniversalLLMClient.getSavedKey(this, provider)
                if (key.isNotEmpty()) {
                    activeList.add(provider)
                }
            }
        }
        
        val chainText = if (activeList.isEmpty()) {
            "Active Chain: (Add API keys to enable)"
        } else {
            "Active Chain: " + activeList.joinToString(" -> ")
        }
        textFallbackChain.text = chainText
    }

    private fun showPicovoiceKeyRequiredDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Picovoice Key Required")
            .setMessage("To enable wake word functionality, you need a Picovoice AccessKey. You can get a free key from the Picovoice Console. Note: The Picovoice dashboard might not be available on mobile browsers sometimes - you may need to use a desktop browser.")
            .setPositiveButton("Get Key") { _, _ ->
                // Try to open Picovoice console
                val url = "https://console.picovoice.ai/login"
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = Uri.parse(url)
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open link. No browser found or link unavailable on mobile. Please use a desktop browser.", Toast.LENGTH_LONG).show()
                    Log.e("SettingsActivity", "Failed to open Picovoice link", e)
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
        
        // Set button text colors to white
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.white)
        )
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.white)
        )
    }

    private fun updateWakeWordButtonState() {
        wakeWordManager.updateButtonState(wakeWordButton)
    }

    private fun showBatteryOptimizationDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.battery_optimization_title))
            .setMessage(getString(R.string.battery_optimization_message))
            .setPositiveButton(getString(R.string.learn_how)) { _, _ ->
                // Open the Tasker FAQ URL
                val url = "https://tasker.joaoapps.com/userguide/en/faqs/faq-problem.html#00"
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = Uri.parse(url)
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open link. No browser found.", Toast.LENGTH_LONG).show()
                    Log.e("SettingsActivity", "Failed to open battery optimization link", e)
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
        
        // Set button text colors to white
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.white)
        )
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.white)
        )
    }

    private fun showSignOutConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Sign Out")
            .setMessage("Are you sure you want to sign out? This will clear all your settings and data.")
            .setPositiveButton("Sign Out") { _, _ ->
                signOut()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun signOut() {
        // Clear User Profile
        val userProfileManager = UserProfileManager(this)
        userProfileManager.clearProfile()

        // Clear all shared preferences for this app
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()


        // Restart the app by navigating to the onboarding screen
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    
    override fun getContentLayoutId(): Int = R.layout.activity_settings
    
    override fun getCurrentNavItem(): BaseNavigationActivity.NavItem = BaseNavigationActivity.NavItem.SETTINGS
}