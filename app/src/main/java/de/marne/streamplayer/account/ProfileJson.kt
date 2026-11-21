package de.marne.streamplayer.account

import org.json.JSONArray
import org.json.JSONObject

// Profile und TitleCollection liegen im gemeinsamen Modul :ui.

fun Profile.toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("color", color).put("createdAt", createdAt)
    .put("list", JSONArray(list)).put("lock", lock)
    .put("collections", JSONArray().apply { collections.forEach { put(it.toJson()) } })

fun Profile.Companion.fromJson(o: JSONObject) = Profile(
    o.getString("id"), o.optString("name", "Profil"), o.optInt("color"), o.optLong("createdAt"),
    o.optJSONArray("list")?.let { a -> List(a.length()) { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty(),
    if (o.isNull("lock")) null else o.optString("lock").takeIf { it.isNotEmpty() },
    o.optJSONArray("collections")?.let { a -> List(a.length()) { TitleCollection.fromJson(a.getJSONObject(it)) } }.orEmpty(),
)

fun TitleCollection.toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("items", JSONArray(items)).put("createdAt", createdAt)

fun TitleCollection.Companion.fromJson(o: JSONObject) = TitleCollection(
    o.getString("id"), o.optString("name"),
    o.optJSONArray("items")?.let { a -> List(a.length()) { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty(),
    o.optLong("createdAt"),
)
