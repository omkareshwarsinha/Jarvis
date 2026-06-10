package com.blurr.voice

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blurr.voice.api.UniversalLLMClient
import com.google.ai.client.generativeai.type.TextPart
import kotlinx.coroutines.launch
import org.json.JSONObject

class ChatActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var editText: EditText
    private lateinit var sendButton: Button
    private val messages = mutableListOf<Message>()
    private lateinit var chatAdapter: ChatAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        recyclerView = findViewById(R.id.recyclerView)
        editText = findViewById(R.id.editText)
        sendButton = findViewById(R.id.sendButton)

        // Get the custom message from the intent
        val customMessage = intent.getStringExtra("custom_message") ?: "Hello! How can I help you today?"

        // Set up the RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(this)
        chatAdapter = ChatAdapter(messages)
        recyclerView.adapter = chatAdapter

        // Display the custom message or default message
        messages.add(Message(customMessage, isUserMessage = false))
        chatAdapter.notifyItemInserted(messages.size - 1)

        // Handle sending messages
        sendButton.setOnClickListener {
            val messageContent = editText.text.toString().trim()
            if (messageContent.isNotEmpty()) {
                // Add the user's message
                messages.add(Message(messageContent, isUserMessage = true))
                chatAdapter.notifyItemInserted(messages.size - 1)
                recyclerView.scrollToPosition(messages.size - 1) // Scroll to the latest message

                // Clear the input field
                editText.text.clear()

                // Call real AI
                lifecycleScope.launch {
                    val responseText = try {
                        val history = messages.map { msg ->
                            val role = if (msg.isUserMessage) "user" else "model"
                            Pair(role, listOf(TextPart(msg.content)))
                        }
                        val rawResponse = UniversalLLMClient.generateResponseWithFallback(history, this@ChatActivity)
                        if (rawResponse != null) {
                            try {
                                val json = JSONObject(rawResponse)
                                json.optString("Reply", rawResponse)
                            } catch (e: Exception) {
                                rawResponse
                            }
                        } else {
                            "I couldn't generate a response. Please check your API keys or internet connection."
                        }
                    } catch (e: Exception) {
                        "Error: ${e.localizedMessage}"
                    }

                    messages.add(Message(responseText, isUserMessage = false))
                    chatAdapter.notifyItemInserted(messages.size - 1)
                    recyclerView.scrollToPosition(messages.size - 1)
                }
            }
        }
    }
}
