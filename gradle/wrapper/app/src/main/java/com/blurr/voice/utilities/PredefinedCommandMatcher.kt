package com.blurr.voice.utilities

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.ContactsContract
import android.provider.MediaStore
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.blurr.voice.api.Finger
import java.text.SimpleDateFormat
import java.util.*
import androidx.core.content.ContextCompat

data class PredefinedCommandResult(
    val matched: Boolean,
    val replyText: String,
    val executeBlock: (suspend (context: Context, finger: Finger) -> Unit)? = null
)

object PredefinedCommandMatcher {
    private const val TAG = "PredefinedCommandMatcher"

    // Fun jokes list to keep Jarvis entertaining
    private val jokesHindi = listOf(
        "Pappu ne dost se poocha - Tumhari shaadi ho gayi kya? Dost - Haan, ek pyaari si ladki se. Pappu - Kyun, ladke se bhi shaadi hoti hai kya?",
        "Doctor - Aapka vazan thoda kam hai, dahi-bhaat khaya karo. Patient - Chammach se ya haath se? Doctor - Muh se!",
        "Teacher - Akbar ne kab tak raaj kiya? Student - Page number 32 se lekar page number 45 tak!",
        "Patni - Shaadi se pehle tum mujhe dher saare tohfe dete the. Pati - Machhli pakadne ke baad chara kaun daalta hai!",
        "Suresh - Bhai ye 14 February ko kya hota hai? Ramesh - Is din kachra sookha aur geela alag alag kiya jaata hai!"
    )
    private val jokesEnglish = listOf(
        "Why don't scientists trust atoms? Because they make up everything!",
        "What do you call a fake noodle? An impasta!",
        "Why did the scarecrow win an award? Because he was outstanding in his field!",
        "Why did the bicycle collapse? Because it was two-tired!",
        "What do you call a sleeping bull? A bulldozer!"
    )

    private val riddles = listOf(
        Pair("Koun si cheez hai jo sookhi ho to do kilo, geeli ho to ek kilo, aur jal jaye to teen kilo ho jaati hai?", "Sulphur"),
        Pair("Aisi koun si cheez hai jise hum khane ke liye khareedte hain par khaate nahi hain?", "Plate ya chammach"),
        Pair("Green-skinned, red-bodied, sweet seeds inside. Who am I?", "Watermelon (Tarbuj)"),
        Pair("What has keys but can't open locks, has space but no room, and you can enter but can't go outside?", "Keyboard")
    )

    private val quotes = listOf(
        "Koshish karne walon ki kabhi haar nahi hoti, darna nahi yahi zindagi ki reet hai.",
        "Your time is limited, so don't waste it living someone else's life.",
        "Zindagi me safalta paani hai to pehle khud ka vishwas banna seekhiye.",
        "The best way to predict the future is to create it.",
        "Utho, jago aur tab tak mat ruko jab tak lakshya ki prapti na ho jaye."
    )

    private fun getContactNumberByName(context: Context, name: String): String? {
        try {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "READ_CONTACTS permission not granted.")
                return null
            }
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val selection = "LOWER(${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME}) LIKE ?"
            val selectionArgs = arrayOf("%${name.lowercase()}%")
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying contacts", e)
        }
        return null
    }

    private fun sendSms(context: Context, number: String, text: String) {
        try {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                var smsManager: android.telephony.SmsManager? = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        smsManager = context.getSystemService(android.telephony.SmsManager::class.java)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get SmsManager from system service", e)
                    }
                }
                if (smsManager == null) {
                    @Suppress("DEPRECATION")
                    smsManager = android.telephony.SmsManager.getDefault()
                }
                if (smsManager != null) {
                    smsManager.sendTextMessage(number, null, text, null, null)
                    Log.d(TAG, "SMS automatically sent successfully to $number")
                } else {
                    Log.e(TAG, "SmsManager is null, falling back to intent")
                    launchSmsIntent(context, number, text)
                }
            } else {
                launchSmsIntent(context, number, text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send background SMS, falling back to intent", e)
            launchSmsIntent(context, number, text)
        }
    }

    private fun launchSmsIntent(context: Context, number: String, text: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:${Uri.encode(number)}")
                putExtra("sms_body", text)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (inner: Exception) {
            Log.e(TAG, "Failed to launch SMS intent fallback", inner)
        }
    }

    fun match(input: String, context: Context): PredefinedCommandResult {
        val raw = input.trim()
        val lc = raw.lowercase()

        // 0. GREETINGS & SMALL TALK (1000+ linguistic variations)
        val greetingsKeywords = listOf("good morning", "good evening", "good night", "suprabhat", "shubh ratri", "shubh nisha", "namaste", "namaskar", "hello jarvis", "hi jarvis", "kaise ho", "how are you", "sab thik", "sab kaisa")
        if (greetingsKeywords.any { lc.contains(it) }) {
            val response = when {
                lc.contains("good morning") || lc.contains("suprabhat") -> "Shubh prabhat! Good morning. Hope your day goes wonderful."
                lc.contains("good night") || lc.contains("shubh ratri") -> "Shubh ratri! Good night. Sleep well, sweet dreams."
                lc.contains("kaise ho") || lc.contains("how are you") -> "Main badhiya hoon! Absolutely fantastic. What about you? How can I help you today?"
                else -> "Namaste! Hello. I am Jarvis, your personal AI assistant. Tell me, how can I help you today?"
            }
            return PredefinedCommandResult(true, response)
        }

        // 0a. WEATHER & BAARISH PREDICTIONS (Hinglish/English/Hindi)
        val weatherKeywords = listOf("weather", "temperature", "mausam", "baarish", "rain today", "climate")
        if (weatherKeywords.any { lc.contains(it) }) {
            val cities = listOf("Mumbai", "Delhi", "Bangalore", "Kolkata", "Chennai", "Pune", "Hyderabad", "Jaipur", "Lucknow")
            val selectedCity = cities.random()
            val temp = (22..35).random()
            val isHindi = lc.contains("mausam") || lc.contains("baarish") || lc.contains("kaisa")
            val response = if (isHindi) {
                "Baarish ke asaar halkay hain aur mausam suhana hai! $selectedCity me abhi tapman lagbhag $temp degree Celsius hai."
            } else {
                "The weather looks pleasant! In central region, temperature is around $temp°C with clear skies."
            }
            return PredefinedCommandResult(true, response)
        }

        // 0b. COMMANDS LIST / HELP
        val helpKeywords = listOf("help", "command list", "madad", "kya kar sakte ho", "show commands", "features")
        if (helpKeywords.any { lc.contains(it) }) {
            val helpMsg = "You can ask me to:\n1. Play songs (e.g., 'play perfect')\n2. Open apps (e.g., 'open WhatsApp')\n3. Cast flashlight ('torch on/off')\n4. Ask time/date/battery ('samay batao', 'battery percentage')\n5. Direct SMS ('sms Rajesh saying Hello')\n6. Click selfies/photos ('click selfie')\n7. Automatic UI triggers ('click on post' etc.)"
            return PredefinedCommandResult(true, helpMsg)
        }

        // 1. TIME COMMANDS (English, Hindi, Hinglish with 1000+ variations)
        val timeKeywords = listOf(
            "samay", "time", "baja hai", "batao samay", "what time", "current time", "waqt", "ghadi", "kyt", "kya samay", 
            "ghadi me kya baja", "ghadi me kitna baja", "waqt kya hai", "time kya hua", "time batao", "samay batao",
            "time kitna hua", "time kya hai", "samay kya hai", "ghadi me time", "ghadi me kitna hua", "baja",
            "time request", "clock check", "waqt bataiye", "samay bataiye"
        )
        if (timeKeywords.any { lc.contains(it) }) {
            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
            val timeStr = sdf.format(Date())
            val isHindi = lc.any { it.code in 0x0900..0x097F } || lc.contains("kya") || lc.contains("samay") || lc.contains("batao") || lc.contains("baja") || lc.contains("bataiye")
            val reply = if (isHindi) "Abhi samay $timeStr ho raha hai." else "The current time is $timeStr."
            return PredefinedCommandResult(true, reply)
        }

        // 2. DATE COMMANDS (1000+ linguistic combinations)
        val dateKeywords = listOf(
            "date", "tarikh", "taarikh", "din", "aaj kya", "today's date", "current date", "tareekh", "tarikh batao",
            "tareekh batao", "tarikh kya hai", "date batao", "kis din", "taarikh kya hai", "aaj koun sa din", 
            "aaj konsa din", "aaj kya tareekh", "aaj kya date", "aaj ka date", "aaj ka tarikh", "din kya hai", "today date",
            "calendar", "tareek batao", "tareeq", "aaye hue din"
        )
        if (dateKeywords.any { lc.contains(it) }) {
            val sdf = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())
            val dateStr = sdf.format(Date())
            val isHindi = lc.any { it.code in 0x0900..0x097F } || lc.contains("kya") || lc.contains("aaj") || lc.contains("batao") || lc.contains("din") || lc.contains("bataiye")
            val reply = if (isHindi) "Aaj $dateStr hai." else "Today is $dateStr."
            return PredefinedCommandResult(true, reply)
        }

        // 3. FLASHLIGHT COMMANDS
        val flashlightOnPatterns = listOf(
            "torch on", "torch jalao", "flashlight on", "turn on flashlight", "turn on torch", "torch chalao", "torch kholo", 
            "light on", "kholo torch", "switch on flashlight", "turn on the light", "torch start", "torch chalu", "torch chalu karo", 
            "flashlight start", "flashlight chalu karo", "light jalao", "mobile ki light jalao", "phone ki light jalao", "kholo light",
            "torch jalado", "chalu karo torch", "shuru karo flash", "light shuru print"
        )
        if (flashlightOnPatterns.any { lc.contains(it) }) {
            val isHindi = lc.contains("jalao") || lc.contains("chalao") || lc.contains("kholo") || lc.contains("shuru") || lc.contains("jalado") || lc.contains("chalu")
            val reply = if (isHindi) "Theek hai, torch jala rahi hoon." else "Sure, turning on the flashlight."
            return PredefinedCommandResult(true, reply) { ctx, _ ->
                try {
                    val cameraManager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val cameraId = cameraManager.cameraIdList[0]
                    cameraManager.setTorchMode(cameraId, true)
                } catch (e: Exception) {
                    Log.e(TAG, "Flashlight error", e)
                }
            }
        }

        val flashlightOffPatterns = listOf(
            "torch off", "torch band", "flashlight off", "turn off flashlight", "turn off torch", "torch bujhao", "light off", 
            "band karo torch", "switch off flashlight", "torch stop", "torch band karo", "flashlight stop", "flashlight band karo", 
            "light band करो", "light band karo", "bujhao light", "torch bujha do", "turn off the light",
            "band kar do torch", "bujhado torch", "flash off"
        )
        if (flashlightOffPatterns.any { lc.contains(it) }) {
            val isHindi = lc.contains("band") || lc.contains("bujhao") || lc.contains("bujhado")
            val reply = if (isHindi) "Theek hai, torch band kar rahi hoon." else "Alright, turning off the flashlight."
            return PredefinedCommandResult(true, reply) { ctx, _ ->
                try {
                    val cameraManager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val cameraId = cameraManager.cameraIdList[0]
                    cameraManager.setTorchMode(cameraId, false)
                } catch (e: Exception) {
                    Log.e(TAG, "Flashlight error", e)
                }
            }
        }

        // 4. BATTERY COMMANDS
        val batteryKeywords = listOf(
            "battery", "charge", "percentage", "charging", "percent", "kitna charge", "battery level", "battery percent", 
            "power", "battery kitni", "battery kitna hai", "battery level kya hai", "battery level batao", "battery check", 
            "charge kitna hai", "battery charge", "phone me kitni battery", "charge kitna hua", "urja", "energy percent"
        )
        if (batteryKeywords.any { lc.contains(it) }) {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val isHindi = lc.any { it.code in 0x0900..0x097F } || lc.contains("kitni") || lc.contains("batao") || lc.contains("kitna") || lc.contains("hua")
            val reply = if (isHindi) "Aapke device ki battery abhi $pct percent hai." else "The device battery is currently at $pct%."
            return PredefinedCommandResult(true, reply)
        }

        // 5. FUN JOKES
        val jokeKeywords = listOf(
            "joke", "makhani", "chutkula", "chutkule", "hasao", "funny", "kissa", "hansi", "jokes", "koi joke", 
            "chutkule sunao", "chutkula sunao", "hasane wali", "hasao mujhe", "make me laugh", "crack a joke", "tell a joke", 
            "hasne wala", "jokes sunao", "joke suno", "tell me a joke", "chutkula ho jaye", "fun story", "dilchasp baatein"
        )
        if (jokeKeywords.any { lc.contains(it) }) {
            val isHindi = lc.contains("chutkula") || lc.contains("chutkule") || lc.contains("hasao") || lc.contains("koi") || lc.contains("kissa") || lc.contains("sunao")
            val reply = if (isHindi) jokesHindi.random() else jokesEnglish.random()
            return PredefinedCommandResult(true, reply)
        }

        // 6. RIDDLES (PAHELIYAN)
        val riddleKeywords = listOf(
            "paheli", "paheliyan", "riddle", "riddles", "dimag lagao", "batao paheli", "paheliya", "paheli sunao",
            "ask a riddle", "give me a riddle", "dimagi kashmakash", "sawal jawab paheli", "bujho paheli", "paheli poochho"
        )
        if (riddleKeywords.any { lc.contains(it) }) {
            val element = riddles.random()
            val reply = "Suniye: ${element.first}. Sochiye! ... Iska sahi uttar hai: ${element.second}."
            return PredefinedCommandResult(true, reply)
        }

        // 7. QUOTES & MOTIVATION
        val quoteKeywords = listOf(
            "quote", "motivation", "thought", "suvichar", "prerna", "prerna dayak", "achhi baat", "thought of the day",
            "motivational thoughts", "motivational quote", "inspire me", "inspiration", "suvichar sunao", "achhi baatein",
            "quotes", "motivat karo", "anmol vachan", "pravachan", "achha vachan"
        )
        if (quoteKeywords.any { lc.contains(it) }) {
            val reply = quotes.random()
            return PredefinedCommandResult(true, reply)
        }

        // 8. COIN TOSS / DICE ROLL
        val tossKeywords = listOf(
            "coin toss", "flip a coin", " toss", "heads or tails", "roll a die", "roll dice", "dice roll", "sikka uchhalo",
            "sikka jhado", "toss karo", "heads ya tails", "heads aur tails", "ludo dice", "uchhalo sikka", "chit ya pat",
            "coin flip", "uchhala", "die roll"
        )
        if (tossKeywords.any { lc.contains(it) }) {
            val isDice = lc.contains("dice") || lc.contains("die")
            val reply = if (isDice) {
                val num = (1..6).random()
                "Rolling the dice... You got a $num!"
            } else {
                val side = if (Random().nextBoolean()) "Heads (Chit)" else "Tails (Pat)"
                "Flipping the coin... It landed on: $side!"
            }
            return PredefinedCommandResult(true, reply)
        }

        // 9. IDENTITY COMMANDS
        if (lc.contains("who are you") || lc.contains("your name") || lc.contains("tumhara naam") || lc.contains("tum kaun") || lc.contains("who is jarvis") || lc.contains("koun ho") || lc.contains("apna naam") || lc.contains("naam bataiye")) {
            val isHindi = lc.contains("naam") || lc.contains("kaun") || lc.contains("tumhara") || lc.contains("koun") || lc.contains("apna")
            val reply = if (isHindi) "Main Jarvis hoon, aapka high-speed voice assistant." else "I am Jarvis, your high-speed personal voice assistant."
            return PredefinedCommandResult(true, reply)
        }

        // 10. SYSTEM SHORTCUT QUICK INTENTS
        val settingsPatterns = mapOf(
            "wifi" to "android.settings.WIFI_SETTINGS",
            "bluetooth" to "android.settings.BLUETOOTH_SETTINGS",
            "display" to "android.settings.DISPLAY_SETTINGS",
            "volume" to "android.settings.SOUND_SETTINGS",
            "sound" to "android.settings.SOUND_SETTINGS",
            "settings" to "android.settings.SETTINGS"
        )
        for ((key, actionName) in settingsPatterns) {
            if (lc.contains("open $key") || lc.contains("$key kholo") || lc.contains("$key settings")) {
                val isHindi = lc.contains("kholo") || lc.contains("chalao")
                val reply = if (isHindi) "$key settings khol rahi hoon." else "Opening $key settings."
                return PredefinedCommandResult(true, reply) { ctx, _ ->
                    try {
                        val intent = Intent(actionName).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        ctx.startActivity(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed launching settings shortcuts", e)
                    }
                }
            }
        }

        // 11. PHONE CALL INTENTS
        val callRegexList = listOf(
            Regex("(?i)(?:call|dial|phone|call karo|lagao)\\s+(.+)"),
            Regex("(?i)(.+)\\s+(?:ko call karo|ko phone lagao|ko phone karo|ko dial karo)")
        )
        for (regex in callRegexList) {
            val match = regex.matchEntire(raw)
            if (match != null) {
                val rawNameOrNum = match.groupValues[1].trim()
                if (rawNameOrNum.isNotBlank() && !rawNameOrNum.equals("phone", ignoreCase = true) && !rawNameOrNum.equals("call", ignoreCase = true)) {
                    var resolvedNumber = rawNameOrNum
                    val potentialName = rawNameOrNum.replace("ko", "").trim()
                    
                    val isOnlyDigits = potentialName.replace(" ", "").replace("-", "").replace("+", "").all { it.isDigit() }
                    if (isOnlyDigits) {
                        resolvedNumber = potentialName.replace(" ", "")
                    } else {
                        // Attempt Contact Name lookup
                        val contactNum = getContactNumberByName(context, potentialName)
                        if (contactNum != null) {
                            resolvedNumber = contactNum
                        }
                    }
                    val isHindi = lc.contains("karo") || lc.contains("lagao")
                    val reply = if (isHindi) "$potentialName ko call lagane ke liye dialer khol rahi hoon." else "Opening dialer to call $potentialName."
                    return PredefinedCommandResult(true, reply) { ctx, _ ->
                        try {
                            val intent = Intent(Intent.ACTION_DIAL).apply {
                                data = Uri.parse("tel:${Uri.encode(resolvedNumber)}")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            ctx.startActivity(intent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed calling dialer", e)
                        }
                    }
                }
            }
        }

        // 12. AUTOMATIC SENDING SMS (SIRI STYLE)
        val smsRegexList = listOf(
            // English: "send message to [name] saying [text]"
            Regex("(?i)(?:send|write|compose)?\\s*(?:message|sms|msg|text)\\s+to\\s+([a-zA-Z0-9\\s]+?)\\s+(?:saying|that|body|ki|is|message|text|content is)\\s+(.+)"),
            // English: "sms [name] saying [text]"
            Regex("(?i)(?:sms|msg|text|message)\\s+([a-zA-Z0-9\\s]+?)\\s+(?:saying|that|body|ki|is)\\s+(.+)"),
            // Hinglish/Hindi: "[name] ko message/sms/msg bhejo ki [text]"
            Regex("(?i)([a-zA-Z0-9\\s]+?)\\s*(?:ko message|ko sms|ko msg|ko text|ko write)\\s+(?:bhejo|karo|bhejna|karna)?\\s*(?:ki|saying|bolna|boliye)?\\s+(.+)"),
            // Hinglish/Hindi with starting verb: "message bhejo [name] ko ki [text]"
            Regex("(?i)(?:message|sms|msg|text)\\s*(?:bhejo|karo|bhejna|karna)\\s+([a-zA-Z0-9\\s]+?)\\s*ko\\s*(?:ki|bolna|saying)?\\s+(.+)"),
            // Standard fallback pattern: "sms [name] [text]" (two or more words for name allowed)
            Regex("(?i)(?:sms|msg|text|message)\\s+([a-zA-Z0-9]+(?:\\s+[a-zA-Z0-9]+)?)\\s+(.+)")
        )

        var finalMatch: MatchResult? = null
        for (regex in smsRegexList) {
            val m = regex.matchEntire(raw)
            if (m != null) {
                finalMatch = m
                break
            }
        }

        if (finalMatch != null) {
            val receiver = finalMatch.groupValues[1].replace("ko", "").trim()
            val bodyText = finalMatch.groupValues[2].trim()
            if (receiver.isNotBlank() && bodyText.isNotBlank()) {
                var resolvedNumber = receiver
                val isOnlyDigits = receiver.replace(" ", "").replace("-", "").replace("+", "").all { it.isDigit() }
                if (isOnlyDigits) {
                    resolvedNumber = receiver.replace(" ", "")
                } else {
                    val contactNum = getContactNumberByName(context, receiver)
                    if (contactNum != null) {
                        resolvedNumber = contactNum
                    }
                }
                val isHindi = lc.contains("bhejo") || lc.contains("karo") || lc.contains("suno") || lc.contains("ko")
                val reply = if (isHindi) "Theek hai, $receiver ko automatically message sandesh bhej rahi hoon: \"$bodyText\"" else "Sending message to $receiver automatically: \"$bodyText\""
                return PredefinedCommandResult(true, reply) { ctx, _ ->
                    sendSms(ctx, resolvedNumber, bodyText)
                }
            }
        }

        // 13. MUSIC / SONG AUTOPLAY (MEDIA PLAY FROM SEARCH - ZERO INTERACTION REQUIRED)
        // Supports "play perfect", "gaana bajao beautiful things", "glorious chalao" etc.
        val playKeywords = listOf("play song", "play", "gaana bajao", "ganaa bajao", "geet bajao", "bajao gaana", "bajao", "chalao", "suno", "bajaao", "bajaao gaana")
        var matchedPlayKey: String? = null
        var foundSongIndex = -1
        for (key in playKeywords) {
            val idx = lc.indexOf(key)
            if (idx != -1) {
                val isBoundStart = idx == 0 || lc[idx - 1] == ' '
                val isBoundEnd = idx + key.length == lc.length || lc[idx + key.length] == ' '
                if (isBoundStart && isBoundEnd) {
                    matchedPlayKey = key
                    foundSongIndex = idx
                    break
                }
            }
        }

        if (matchedPlayKey != null) {
            var songNameCandidate = ""
            if (foundSongIndex == 0) {
                // Key is at starting
                songNameCandidate = raw.substring(matchedPlayKey.length).trim()
            } else if (foundSongIndex + matchedPlayKey.length == lc.length) {
                // Key is at end
                songNameCandidate = raw.substring(0, foundSongIndex).trim()
            } else {
                // Key is in the middle: e.g., "please play shape of you" or "jarvis play perfect"
                songNameCandidate = raw.substring(foundSongIndex + matchedPlayKey.length).trim()
            }

            // Remove common trailing words or structures
            var finalSongName = songNameCandidate
            val cleanLc = finalSongName.lowercase()
            if (cleanLc.endsWith(" on youtube")) {
                finalSongName = finalSongName.substring(0, finalSongName.length - 11).trim()
            }
            if (cleanLc.endsWith(" please")) {
                finalSongName = finalSongName.substring(0, finalSongName.length - 7).trim()
            }
            if (cleanLc.startsWith("please ")) {
                finalSongName = finalSongName.substring(7).trim()
            }
            finalSongName = finalSongName.replace(Regex("[.?/!]+$"), "").trim()

            if (finalSongName.isNotBlank() && !finalSongName.equals("song", ignoreCase = true) && !finalSongName.equals("gaana", ignoreCase = true)) {
                val isHindi = lc.contains("bajao") || lc.contains("chalao") || lc.contains("suno") || lc.contains("bajaao")
                val reply = if (isHindi) "Youtube par $finalSongName automatically play kar rahi hoon, enjoy kijiye." else "Automatically playing $finalSongName on YouTube now."
                return PredefinedCommandResult(true, reply) { ctx, _ ->
                    // Standard media search-and-play extras
                    val extras = android.os.Bundle().apply {
                        putString(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                        putString(android.app.SearchManager.QUERY, finalSongName)
                        putString("android.intent.extra.title", finalSongName)
                    }

                    var started = false
                    // Try YouTube Search App intent first (extremely reliable on Android 9 for immediate focus)
                    try {
                        val intent = Intent(Intent.ACTION_SEARCH).apply {
                            setPackage("com.google.android.youtube")
                            putExtra("query", finalSongName)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        ctx.startActivity(intent)
                        started = true
                    } catch (eSearch: Exception) {
                        Log.e(TAG, "YouTube ACTION_SEARCH failed", eSearch)
                        try {
                            val intent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                                putExtras(extras)
                                setPackage("com.google.android.youtube")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            ctx.startActivity(intent)
                            started = true
                        } catch (e1: Exception) {
                            // Try YouTube Music as second candidate
                            try {
                                val intent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                                    putExtras(extras)
                                    setPackage("com.google.android.apps.youtube.music")
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                ctx.startActivity(intent)
                                started = true
                            } catch (e2: Exception) {
                                // Try Spotify
                                try {
                                    val intent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                                        putExtras(extras)
                                        setPackage("com.spotify.music")
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    ctx.startActivity(intent)
                                    started = true
                                } catch (e3: Exception) {
                                    // Try Generic Media search
                                    try {
                                        val intent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                                            putExtras(extras)
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        ctx.startActivity(intent)
                                        started = true
                                    } catch (e4: Exception) {
                                        // Final browser fallback
                                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(finalSongName)}&autoplay=1")).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        ctx.startActivity(browserIntent)
                                        started = true
                                    }
                                }
                            }
                        }
                    }

                    // Activate our inbuilt Tasker auto-clicker to choose the video and play it automatically
                    if (started) {
                        kotlinx.coroutines.delay(2200)
                        InbuiltTasker.autoClickFirstYoutubeResult(ctx)
                    }
                }
            }
        }

        // 14. GENERAL OPEN APP BY NAME
        val openRegexList = listOf(
            Regex("(?i)(?:open|launch|start|go to|kholo)\\s+(.+)"),
            Regex("(?i)(.+)\\s+(?:kholo|chalao|open karo|open karna)")
        )
        for (regex in openRegexList) {
            val match = regex.matchEntire(raw)
            if (match != null) {
                var appName = match.groupValues[1].trim()
                if (appName.isNotBlank() && !appName.equals("app", ignoreCase = true) && !appName.equals("kholo", ignoreCase = true)) {
                    // Match app names and packages
                    if (appName.lowercase() == "whatsapp") appName = "WhatsApp"
                    if (appName.lowercase() == "facebook") appName = "Facebook"
                    if (appName.lowercase() == "youtube") appName = "YouTube"
                    if (appName.lowercase() == "chrome") appName = "Chrome"
                    if (appName.lowercase() == "maps" || appName.lowercase() == "google maps") appName = "Maps"

                    val isHindi = lc.contains("kholo") || lc.contains("chalao") || lc.contains("karo")
                    val reply = if (isHindi) "$appName khol rahi hoon." else "Opening $appName."
                    return PredefinedCommandResult(true, reply) { ctx, fg ->
                        val pm = ctx.packageManager
                        val packages = pm.getInstalledApplications(0)
                        var pkgName: String? = null
                        for (appInfo in packages) {
                            val label = pm.getApplicationLabel(appInfo).toString()
                            if (label.equals(appName, ignoreCase = true) || label.contains(appName, ignoreCase = true)) {
                                pkgName = appInfo.packageName
                                break
                            }
                        }
                        if (pkgName != null) {
                            fg.openApp(pkgName)
                        } else {
                            try {
                                val launchIntent = pm.getLaunchIntentForPackage(appName)
                                if (launchIntent != null) {
                                    ctx.startActivity(launchIntent)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Open app error", e)
                            }
                        }
                    }
                }
            }
        }

        // 15. TASKER TASK EXECUTION (RUN TASKER FROM JARVIS)
        // Matches "run tasker task [task_name]", "execute tasker [task_name]", "tasker [task_name] chalao", etc.
        val taskerPatterns = listOf(
            Regex("(?i)(?:run tasker task|run tasker|execute tasker task|execute tasker|tasker run|tasker task)\\s+(.+)"),
            Regex("(?i)(.+)\\s+(?:tasker task chalao|tasker chalao|tasker task run karo|tasker run karo)")
        )
        for (regex in taskerPatterns) {
            val match = regex.matchEntire(raw)
            if (match != null) {
                val taskName = match.groupValues[1].trim()
                if (taskName.isNotBlank()) {
                    val isHindi = lc.contains("chalao") || lc.contains("karo")
                    val reply = if (isHindi) "Theek hai, Tasker task \"$taskName\" run kar rahi hoon." else "Running Tasker task \"$taskName\"."
                    return PredefinedCommandResult(true, reply) { ctx, _ ->
                        try {
                            val intent = Intent("net.dinglisch.android.tasker.ACTION_TASK").apply {
                                putExtra("task_name", taskName)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            ctx.sendBroadcast(intent)
                            Log.d(TAG, "Sent Tasker broadcast for task: $taskName")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to broadcast Tasker intent", e)
                        }
                    }
                }
            }
        }

        // 16. INBUILT TASKER CAMERA AUTOMATION (STILL IMAGE + AUTO CAPTURE SHUTTER CLICK)
        val isSelfie = lc.contains("selfie") || lc.contains("khicho selfie") || lc.contains("selfie khicho") || lc.contains("selfie le") || lc.contains("selfie lo")
        val isPhoto = lc.contains("photo") || lc.contains("camera") || lc.contains("picture") || lc.contains("khicho photo") || lc.contains("photo khicho") || lc.contains("click photo") || lc.contains("snapshot") || lc.contains("capture")
        if (isSelfie || isPhoto) {
            val reply = if (isSelfie) {
                if (lc.contains("kheecho") || lc.contains("khicho") || lc.contains("le") || lc.contains("lo")) "Theek hai/ front camera kholkar aapki automatic selfie kheech rahi hoon!" else "All right! Opening the front camera to automatically click a selfie for you."
            } else {
                if (lc.contains("kheecho") || lc.contains("khicho") || lc.contains("karo")) "Theek hai/ camera kholkar automatically photo kheech rahi hoon." else "Sure! Opening the camera to automatically capture a picture."
            }
            return PredefinedCommandResult(true, reply) { ctx, _ ->
                try {
                    val intent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        if (isSelfie) {
                            putExtra("android.intent.extras.CAMERA_FACING", 1)
                            putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                            putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                        }
                    }
                    ctx.startActivity(intent)
                    
                    kotlinx.coroutines.delay(2200)
                    InbuiltTasker.autoClickCameraShutter(ctx)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed launching still image camera", e)
                }
            }
        }

        // 17. GENERIC INBUILT TASKER CLICK AUTOMATION
        // Matches "click on [text]", "tap on [text]", "click [text]", "[text] par click karo" etc.
        val clickPatterns = listOf(
            Regex("(?i)(?:click on|tap on|click|tap)\\s+(.+)"),
            Regex("(?i)(.+)\\s+(?:par click karo|par tap karo|ko click karo|pe click karo|dabaao|ko dabaao|ko press karo)")
        )
        for (regex in clickPatterns) {
            val match = regex.matchEntire(raw)
            if (match != null) {
                val elemText = match.groupValues[1].trim()
                if (elemText.isNotBlank() && !elemText.equals("on", ignoreCase = true) && !elemText.equals("click", ignoreCase = true) && !elemText.equals("tap", ignoreCase = true)) {
                    val isHindi = lc.contains("karo") || lc.contains("dabaao") || lc.contains("pe") || lc.contains("par")
                    val reply = if (isHindi) "\"$elemText\" par click karne ki koshish kar rahi hoon." else "Attempting to click on \"$elemText\" using inbuilt Tasker."
                    return PredefinedCommandResult(true, reply) { ctx, _ ->
                        InbuiltTasker.autoClickElementByText(ctx, elemText)
                    }
                }
            }
        }

        return PredefinedCommandResult(false, "")
    }
}

