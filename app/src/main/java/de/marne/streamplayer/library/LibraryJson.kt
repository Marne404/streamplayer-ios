package de.marne.streamplayer.library

import org.json.JSONObject

// Library liegt im gemeinsamen Modul :ui.

fun Library.toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("color", color).put("createdAt", createdAt).apply {
    if (shared) put("shared", true)
    invite?.let { put("invite", it) }
    if (joined) put("joined", true)
    owner?.let { put("owner", it) }
}

fun Library.Companion.fromJson(o: JSONObject) = Library(
    id = o.getString("id"),
    name = o.optString("name"),
    color = o.optInt("color"),
    createdAt = o.optLong("createdAt"),
    shared = o.optBoolean("shared"),
    invite = o.optString("invite").takeIf { it.isNotBlank() },
    joined = o.optBoolean("joined"),
    owner = o.optString("owner").takeIf { it.isNotBlank() },
)
