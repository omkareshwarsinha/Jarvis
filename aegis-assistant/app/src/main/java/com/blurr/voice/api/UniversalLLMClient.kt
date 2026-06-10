package com.blurr.voice.api

import android.content.Context
import android.util.Log
import com.blurr.voice.MyApplication
import com.blurr.voice.utilities.NetworkConnectivityManager
import com.google.ai.client.generativeai.type.TextPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object UniversalLLMClient {
    private const val TAG = "UniversalLLMClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Retrieve API Keys from SharedPreferences
    fun getSavedKey(context: Context, provider: String): String {
        val prefs = context.getSharedPreferences("BlurrSettings", Context.MODE_PRIVATE)
        return prefs.getString("api_key_${provider.lowercase()}", "") ?: ""
    }

    fun saveKey(context: Context, provider: String, key: String) {
        val prefs = context.getSharedPreferences("BlurrSettings", Context.MODE_PRIVATE)
        prefs.edit().putString("api_key_${provider.lowercase()}", key).apply()
    }

    fun getPreferredProvider(context: Context): String {
        val prefs = context.getSharedPreferences("BlurrSettings", Context.MODE_PRIVATE)
        return prefs.getString("preferred_llm_provider", "Gemini") ?: "Gemini"
    }

    fun setPreferredProvider(context: Context, provider: String) {
        val prefs = context.getSharedPreferences("BlurrSettings", Context.MODE_PRIVATE)
        prefs.edit().putString("preferred_llm_provider", provider).apply()
    }

    fun getSelectedModel(context: Context, provider: String): String {
        val prefs = context.getSharedPreferences("BlurrSettings", Context.MODE_PRIVATE)
        val defaultModel = when (provider.lowercase()) {
            "gemini" -> "gemini-1.5-flash"
            "openai" -> "gpt-4o-mini"
            "grok" -> "grok-beta"
            "openrouter" -> "google/gemini-flash-1.5"
            else -> "gemini-1.5-flash"
        }
        return prefs.getString("model_${provider.lowercase()}", defaultModel) ?: defaultModel
    }

    fun saveSelectedModel(context: Context, provider: String, model: String) {
        val prefs = context.getSharedPreferences("BlurrSettings", Context.MODE_PRIVATE)
        prefs.edit().putString("model_${provider.lowercase()}", model).apply()
    }

    private fun cleanHtmlResponse(text: String?): String? {
        if (text == null || text.isBlank()) return text
        
        // If it's a full HTML error page from Cloudflare or a web server, 
        // we should NEVER speak the HTML raw or do a naive regex strip of tags.
        if (text.contains("<!DOCTYPE html", ignoreCase = true) || 
            text.contains("<html", ignoreCase = true) || 
            text.contains("<head", ignoreCase = true) || 
            text.contains("<title", ignoreCase = true)) {
            Log.e(TAG, "HTML webpage detected in response. Rejecting and returning clean fallback.")
            return """{"Type": "Reply", "Reply": "Apologies, the server returned an invalid response. Please try again.", "Instruction": "", "Should End": "Continue"}"""
        }

        // Gracefully strip markdown code block wraps without destroying the content inside
        var cleaned = text.replace(Regex("^```[a-zA-Z0-9]*\\s*", RegexOption.MULTILINE), "")
        cleaned = cleaned.replace(Regex("```\\s*$"), "")
        
        // Strip any remaining XML/HTML tags
        cleaned = cleaned.replace(Regex("<[^>]*>"), "")
        
        return cleaned.trim()
    }

    /**
     * Tries generating content using the preferred provider. If it fails or is unconfigured,
     * falls back through the other active providers in order.
     */
    suspend fun generateResponseWithFallback(
        chat: List<Pair<String, List<Any>>>,
        context: Context = MyApplication.appContext
    ): String? {
        val primary = getPreferredProvider(context)
        val providers = listOf("Gemini", "OpenAI", "Grok", "OpenRouter", "Pollinations")
        
        // Put preferred provider first if it belongs to standard providers
        val order = mutableListOf<String>()
        if (providers.contains(primary)) {
            order.add(primary)
        } else {
            order.add("Pollinations") // Use Pollinations as safe default (since it has no cost / token requirement)
        }
        providers.forEach { if (it != primary) order.add(it) }

        Log.d(TAG, "Starting LLM request. Preferred provider: $primary. Fallback order: $order")

        for (provider in order) {
            val apiKey = getSavedKey(context, provider)
            if (apiKey.isBlank() && !provider.equals("Pollinations", ignoreCase = true)) {
                Log.d(TAG, "Skipping provider $provider: API key is not set.")
                continue
            }

            val modelName = getSelectedModel(context, provider)
            Log.d(TAG, "Attempting connection to provider: $provider with model: $modelName")

            try {
                val response = when (provider.lowercase()) {
                    "gemini" -> callGeminiDirectly(chat, modelName, apiKey)
                    "openai" -> callOpenAIViaREST(chat, modelName, apiKey)
                    "grok" -> callGrokViaREST(chat, modelName, apiKey)
                    "openrouter" -> callOpenRouterViaREST(chat, modelName, apiKey)
                    "pollinations" -> callPollinationsREST(chat, modelName)
                    else -> null
                }

                if (!response.isNullOrBlank()) {
                    Log.d(TAG, "Successfully generated response from provider: $provider")
                    return cleanHtmlResponse(response)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error invoking provider $provider: ${e.message}", e)
            }
        }

        Log.e(TAG, "All configured LLM providers failed or none have API keys specified. Falling back to Pollinations.")
        val pollinationsRes = callPollinationsREST(chat)
        if (!pollinationsRes.isNullOrBlank()) {
            return cleanHtmlResponse(pollinationsRes)
        }

        Log.e(TAG, "Pollinations fallback failed. Falling back to proxy Gemini.")
        // Final fallback: Use standard Gemini proxy if configured
        return try {
            cleanHtmlResponse(GeminiApi.generateContent(chat))
        } catch (e: Exception) {
            Log.e(TAG, "Proxy fallback failed too: ${e.message}")
            null
        }
    }

    private suspend fun callOllamaViaREST(
        chat: List<Pair<String, List<Any>>>,
        model: String,
        baseUrlOrEmpty: String
    ): String? = withContext(Dispatchers.IO) {
        val endpoint = if (baseUrlOrEmpty.isNotBlank()) baseUrlOrEmpty.trim().removeSuffix("/") else "http://10.0.2.2:11434"
        val url = "$endpoint/api/chat"
        
        val messagesArray = JSONArray()
        chat.forEach { (role, parts) ->
            val textContent = parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
            if (textContent.isNotEmpty()) {
                val messageObj = JSONObject()
                messageObj.put("role", if (role.equals("model", ignoreCase = true)) "assistant" else "user")
                messageObj.put("content", textContent)
                messagesArray.put(messageObj)
            }
        }
        
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
            put("stream", false)
        }

        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) {
                throw Exception("Ollama Error: code=${response.code}, body=$body")
            }
            val json = JSONObject(body)
            if (json.has("message")) {
                val messageObj = json.getJSONObject("message")
                return@withContext messageObj.getString("content")
            }
            null
        }
    }

    private suspend fun callPollinationsREST(
        chat: List<Pair<String, List<Any>>>,
        model: String = "openai"
    ): String? = withContext(Dispatchers.IO) {
        try {
            val messagesArray = formatMessagesForOpenAI(chat)
            val jsonBody = JSONObject().apply {
                put("messages", messagesArray)
                put("model", model) // Dynamic free model mapping
            }
            
            val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("https://text.pollinations.ai/openai/chat/completions")
                .post(requestBody)
                .build()
                
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    // Filter out any HTML responses to prevent speaker/TTS corruption
                    if (body.contains("<html", ignoreCase = true) || body.contains("<!doctype", ignoreCase = true)) {
                        return@withContext null
                    }
                    try {
                        val json = JSONObject(body)
                        val choices = json.getJSONArray("choices")
                        if (choices.length() > 0) {
                            choices.getJSONObject(0).getJSONObject("message").getString("content")
                        } else {
                            body
                        }
                    } catch (e: Exception) {
                        body
                    }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pollinations API fallback error: ${e.message}", e)
            null
        }
    }

    /**
     * Formats chat history as a standard ChatGPT-like messages list
     */
    private fun formatMessagesForOpenAI(chat: List<Pair<String, List<Any>>>): JSONArray {
        val messagesArray = JSONArray()
        chat.forEachIndexed { index, (role, parts) ->
            val messageObj = JSONObject()
            
            // Map the role
            val mappedRole = when {
                index == 0 -> "system" // Emphasize system prompts for initial system directions
                role.equals("model", ignoreCase = true) -> "assistant"
                role.equals("assistant", ignoreCase = true) -> "assistant"
                else -> "user"
            }
            messageObj.put("role", mappedRole)

            val textContent = parts.filterIsInstance<TextPart>()
                .joinToString("\n") { it.text }
            
            if (textContent.isNotEmpty()) {
                messageObj.put("content", textContent)
                messagesArray.put(messageObj)
            }
        }
        return messagesArray
    }

    private suspend fun callGeminiDirectly(
        chat: List<Pair<String, List<Any>>>,
        model: String,
        apiKey: String
    ): String? = withContext(Dispatchers.IO) {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        
        val contentsArray = JSONArray()
        chat.forEach { (role, parts) ->
            val textContent = parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
            if (textContent.isNotEmpty()) {
                val contentObj = JSONObject()
                contentObj.put("role", if (role.equals("model", ignoreCase = true)) "model" else "user")
                
                val partsArray = JSONArray()
                partsArray.put(JSONObject().put("text", textContent))
                contentObj.put("parts", partsArray)
                
                contentsArray.put(contentObj)
            }
        }

        val payload = JSONObject()
        payload.put("contents", contentsArray)

        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) {
                throw Exception("Gemini Direct Error: code=${response.code}, body=$body")
            }
            
            val json = JSONObject(body)
            val candidates = json.getJSONArray("candidates")
            if (candidates.length() > 0) {
                val firstCandidate = candidates.getJSONObject(0)
                val text = firstCandidate.getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                return@withContext text
            }
            null
        }
    }

    private suspend fun callOpenAIViaREST(
        chat: List<Pair<String, List<Any>>>,
        model: String,
        apiKey: String
    ): String? = withContext(Dispatchers.IO) {
        val url = "https://api.openai.com/v1/chat/completions"
        val payload = JSONObject()
        payload.put("model", model)
        payload.put("messages", formatMessagesForOpenAI(chat))

        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) {
                throw Exception("OpenAI Error: code=${response.code}, body=$body")
            }
            val json = JSONObject(body)
            val choices = json.getJSONArray("choices")
            if (choices.length() > 0) {
                return@withContext choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            }
            null
        }
    }

    private suspend fun callGrokViaREST(
        chat: List<Pair<String, List<Any>>>,
        model: String,
        apiKey: String
    ): String? = withContext(Dispatchers.IO) {
        val url = "https://api.x.ai/v1/chat/completions"
        val payload = JSONObject()
        payload.put("model", model)
        payload.put("messages", formatMessagesForOpenAI(chat))

        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) {
                throw Exception("Grok Error: code=${response.code}, body=$body")
            }
            val json = JSONObject(body)
            val choices = json.getJSONArray("choices")
            if (choices.length() > 0) {
                return@withContext choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            }
            null
        }
    }

    private suspend fun callOpenRouterViaREST(
        chat: List<Pair<String, List<Any>>>,
        model: String,
        apiKey: String
    ): String? = withContext(Dispatchers.IO) {
        val url = "https://openrouter.ai/api/v1/chat/completions"
        val payload = JSONObject()
        payload.put("model", model)
        payload.put("messages", formatMessagesForOpenAI(chat))

        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("HTTP-Referer", "https://ai.studio/build")
            .addHeader("X-Title", "Jarvis Voice Builder")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) {
                throw Exception("OpenRouter Error: code=${response.code}, body=$body")
            }
            val json = JSONObject(body)
            val choices = json.getJSONArray("choices")
            if (choices.length() > 0) {
                return@withContext choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            }
            null
        }
    }

    /**
     * Lists models of standard providers. Standard HTTP fetch, or curation.
     */
    suspend fun testAndFetchModels(provider: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() && !provider.equals("Ollama", ignoreCase = true)) return@withContext emptyList<String>()
        
        try {
            when (provider.lowercase()) {
                "gemini" -> {
                    // Try to fetch, if error, fallback to quick curation list
                    val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        val body = response.body?.string()
                        if (response.isSuccessful && body != null) {
                            val json = JSONObject(body)
                            val modelsArray = json.getJSONArray("models")
                            val list = mutableListOf<String>()
                            for (i in 0 until modelsArray.length()) {
                                val modelObj = modelsArray.getJSONObject(i)
                                val name = modelObj.getString("name").removePrefix("models/")
                                if (name.contains("gemini", ignoreCase = true) && !name.contains("tuning")) {
                                    list.add(name)
                                }
                            }
                            if (list.isNotEmpty()) return@withContext list
                        }
                    }
                    return@withContext listOf("gemini-1.5-flash", "gemini-2.5-flash", "gemini-2.5-pro", "gemini-1.5-pro")
                }
                "openai" -> {
                    val url = "https://api.openai.com/v1/models"
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .build()
                    client.newCall(request).execute().use { response ->
                        val body = response.body?.string()
                        if (response.isSuccessful && body != null) {
                            val json = JSONObject(body)
                            val dataArray = json.getJSONArray("data")
                            val list = mutableListOf<String>()
                            for (i in 0 until dataArray.length()) {
                                val modelObj = dataArray.getJSONObject(i)
                                val id = modelObj.getString("id")
                                if (id.startsWith("gpt-") && !id.contains("vision") && !id.contains("instruct")) {
                                    list.add(id)
                                }
                            }
                            if (list.isNotEmpty()) return@withContext list
                        }
                    }
                    return@withContext listOf("gpt-4o-mini", "gpt-4o", "gpt-3.5-turbo")
                }
                "grok" -> {
                    // Grok has specific models
                    return@withContext listOf("grok-beta", "grok-2-1212", "grok-vision-beta")
                }
                "openrouter" -> {
                    val url = "https://openrouter.ai/api/v1/models"
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .build()
                    client.newCall(request).execute().use { response ->
                        val body = response.body?.string()
                        if (response.isSuccessful && body != null) {
                            val json = JSONObject(body)
                            val dataArray = json.getJSONArray("data")
                            val list = mutableListOf<String>()
                            for (i in 0 until dataArray.length()) {
                                val modelObj = dataArray.getJSONObject(i)
                                val id = modelObj.getString("id")
                                list.add(id)
                            }
                            if (list.isNotEmpty()) return@withContext list.take(15) // Limit to top 15 models
                        }
                    }
                    return@withContext listOf(
                        "google/gemini-flash-1.5",
                        "meta-llama/llama-3-8b-instruct",
                        "openai/gpt-4o-mini",
                        "mistralai/mistral-7b-instruct"
                    )
                }
                "pollinations" -> {
                    return@withContext listOf("openai", "mistral", "qwen-coder", "llama")
                }
                "ollama" -> {
                    try {
                        val base = if (apiKey.isNotBlank()) apiKey.trim().removeSuffix("/") else "http://10.0.2.2:11434"
                        val url = "$base/api/tags"
                        val request = Request.Builder().url(url).build()
                        client.newCall(request).execute().use { response ->
                            val body = response.body?.string()
                            if (response.isSuccessful && body != null) {
                                val json = JSONObject(body)
                                val modelsArray = json.getJSONArray("models")
                                val list = mutableListOf<String>()
                                for (i in 0 until modelsArray.length()) {
                                    val modelObj = modelsArray.getJSONObject(i)
                                    list.add(modelObj.getString("name"))
                                }
                                if (list.isNotEmpty()) return@withContext list
                            }
                        }
                    } catch (ex: Exception) {
                        Log.e(TAG, "Ollama connection/tag-listing failed: ${ex.message}")
                    }
                    return@withContext emptyList<String>()
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed testing/listing models for $provider: ${e.message}")
            // Return curated lists as safe fallback
            return@withContext when (provider.lowercase()) {
                "pollinations" -> listOf("openai", "mistral", "qwen-coder", "llama")
                "gemini" -> listOf("gemini-1.5-flash", "gemini-2.5-flash", "gemini-2.5-pro")
                "openai" -> listOf("gpt-4o-mini", "gpt-4o", "gpt-3.5-turbo")
                "grok" -> listOf("grok-beta")
                "openrouter" -> listOf("google/gemini-flash-1.5", "meta-llama/llama-3-8b-instruct", "openai/gpt-4o-mini")
                "ollama" -> emptyList()
                else -> emptyList()
            }
        }
    }
}
