package com.blurr.voice.intents.impl

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.blurr.voice.intents.AppIntent
import com.blurr.voice.intents.ParameterSpec

class SendMessageIntent : AppIntent {
    override val name: String = "SendMessage"
    override fun description(): String = "Send a text message to a phone number."
    override fun parametersSpec(): List<ParameterSpec> = listOf(
        ParameterSpec(
            name = "phone_number",
            type = "string",
            required = true,
            description = "The phone number to send the message to."
        ),
        ParameterSpec(
            name = "message",
            type = "string",
            required = true,
            description = "The body of the message."
        )
    )

    override fun buildIntent(context: Context, params: Map<String, Any?>): Intent? {
        val phoneNumber = params["phone_number"]?.toString()?.trim().orEmpty()
        val message = params["message"]?.toString()?.trim().orEmpty()
        if (phoneNumber.isEmpty()) return null
        
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$phoneNumber")
            putExtra("sms_body", message)
        }
        return intent
    }
}
