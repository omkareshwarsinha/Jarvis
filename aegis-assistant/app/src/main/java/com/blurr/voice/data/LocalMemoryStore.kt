package com.blurr.voice.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object LocalMemoryStore {
    private const val PREFS_NAME = "JarvisLocalMemories"
    private const val KEY_MEMORIES = "memories_list"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun getMemories(context: Context): List<UserMemory> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawJson = prefs.getString(KEY_MEMORIES, null) ?: return emptyList()
        val list = mutableListOf<UserMemory>()
        try {
            val jsonArray = JSONArray(rawJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id", UUID.randomUUID().toString())
                val text = obj.optString("text", "")
                val source = obj.optString("source", "User")
                val dateStr = obj.optString("createdAt", "")
                val date = if (dateStr.isNotEmpty()) {
                    try { dateFormat.parse(dateStr) } catch (e: Exception) { Date() }
                } else {
                    Date()
                }
                list.add(UserMemory(id, text, source, date ?: Date()))
            }
        } catch (e: Exception) {
            Log.e("LocalMemoryStore", "Error loading memories", e)
        }
        return list.sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun saveMemory(context: Context, text: String, source: String = "User"): UserMemory {
        val memories = getMemories(context).toMutableList()
        val newMemory = UserMemory(
            id = UUID.randomUUID().toString(),
            text = text,
            source = source,
            createdAt = Date()
        )
        memories.add(0, newMemory)
        persistMemories(context, memories)
        return newMemory
    }

    @Synchronized
    fun updateMemory(context: Context, id: String, newText: String) {
        val memories = getMemories(context).map {
            if (it.id == id) {
                UserMemory(it.id, newText, it.source, Date())
            } else {
                it
            }
        }
        persistMemories(context, memories)
    }

    @Synchronized
    fun deleteMemory(context: Context, id: String) {
        val memories = getMemories(context).filter { it.id != id }
        persistMemories(context, memories)
    }

    private fun persistMemories(context: Context, list: List<UserMemory>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        for (item in list) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("text", item.text)
            obj.put("source", item.source)
            obj.put("createdAt", dateFormat.format(item.createdAt))
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_MEMORIES, jsonArray.toString()).apply()
        Log.d("LocalMemoryStore", "Persisted ${list.size} memories offline.")
    }
}
