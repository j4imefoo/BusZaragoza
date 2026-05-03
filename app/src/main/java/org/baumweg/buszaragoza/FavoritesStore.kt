package org.baumweg.buszaragoza

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class FavoritesStore(context: Context) {
    private val file = File(context.filesDir, "favoritos.json")

    fun load(): List<Favorite> {
        if (!file.exists()) return emptyList()
        return runCatching { parse(file.readText()) }.getOrDefault(emptyList())
    }

    fun save(favorites: List<Favorite>) {
        file.writeText(toJson(favorites).toString(2))
    }

    fun exportJson(): String = toJson(load()).toString(2)

    fun importJson(text: String): Int {
        val favorites = parse(text)
        save(favorites)
        return favorites.size
    }

    private fun parse(text: String): List<Favorite> {
        val array = JSONArray(text)
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val type = if (o.optString("type") == "TRAM") TransportType.TRAM else TransportType.BUS
                add(
                    Favorite(
                        id = o.optString("id").ifBlank { "${o.optString("line")}-${o.optString("stopId")}" },
                        name = o.optString("name"),
                        line = o.optString("line"),
                        stopId = o.optString("stopId"),
                        stopName = o.optString("stopName"),
                        type = type,
                        code = o.optString("code", o.optString("stopId")),
                        direction = o.optInt("direction", 0),
                        destination = o.optString("destination"),
                        description = o.optString("description"),
                    )
                )
            }
        }
    }

    private fun toJson(favorites: List<Favorite>): JSONArray = JSONArray().apply {
        favorites.forEach { fav ->
            put(JSONObject().apply {
                put("id", fav.id)
                put("name", fav.name)
                put("line", fav.line)
                put("stopId", fav.stopId)
                put("stopName", fav.stopName)
                put("type", fav.type.name)
                put("code", fav.code)
                put("direction", fav.direction)
                put("destination", fav.destination)
                put("description", fav.description)
            })
        }
    }
}
