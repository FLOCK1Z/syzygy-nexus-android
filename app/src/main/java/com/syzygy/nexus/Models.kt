package com.syzygy.nexus

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

fun newId() = UUID.randomUUID().toString()

data class NexusUser(val login: String, val tier: String)

data class Doc(val id: String, val name: String)

data class Message(
    val id: String = newId(),
    val role: String,
    val text: String,
    val status: String = "complete",
    val docs: List<Doc> = emptyList(),
    val images: List<String> = emptyList(),
    val work: WorkMission? = null,
)

data class Conversation(
    val id: String = newId(),
    val title: String = "Nova conversa",
    val model: String = "",
    val messages: List<Message> = emptyList(),
    val updated: Long = System.currentTimeMillis(),
    val temporary: Boolean = false,
)

fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

fun JSONObject.namesList() = keys().asSequence().toList()

fun Message.json() =
    JSONObject()
        .put("id", id)
        .put("role", role)
        .put("text", text)
        .put("status", status)
        .put("docs", JSONArray(docs.map { JSONObject().put("id", it.id).put("name", it.name) }))
        .put("images", JSONArray(images))
        .apply { work?.let { put("work", it.json()) } }

fun Conversation.json() =
    JSONObject()
        .put("id", id)
        .put("title", title)
        .put("model", model)
        .put("messages", JSONArray(messages.map { it.json() }))
        .put("updated", updated)

fun conversationFrom(j: JSONObject): Conversation =
    Conversation(
        j.getString("id"),
        j.getString("title"),
        j.optString("model"),
        j.getJSONArray("messages").objects().map { m ->
            Message(
                m.getString("id"),
                m.getString("role"),
                m.optString("text"),
                if (m.optString("status") == "sending") "stopped"
                else m.optString("status", "complete"),
                m.optJSONArray("docs")?.objects()?.map {
                    Doc(it.getString("id"), it.getString("name"))
                } ?: emptyList(),
                m.optJSONArray("images")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                    ?: emptyList(),
                m.optJSONObject("work")?.let { WorkMission.from(it) },
            )
        },
        j.optLong("updated"),
    )

fun passwordProblem(value: String): String? =
    when {
        value.length !in 15..128 -> "Use de 15 a 128 caracteres."
        !value.any { it in 'a'..'z' } ||
            !value.any { it in 'A'..'Z' } ||
            !value.any { it in '0'..'9' } ||
            !value.any { !it.isLetterOrDigit() && !it.isWhitespace() } ->
            "Inclua maiúscula, minúscula, número e símbolo."
        else -> null
    }
