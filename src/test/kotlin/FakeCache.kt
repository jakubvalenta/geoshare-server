package net.geoshare_app

import kotlinx.serialization.json.Json

class FakeCache : Cache {
    private data class Item(val value: String, val expireAtMillis: Long? = null)

    private val map: MutableMap<String, Item> = mutableMapOf(
        FakeGoogleMapsClient.NOT_FOUND_CACHED_PLACE_ID.sha256Hex() to
            Item(Json.encodeToString(Location(22.22, 111.11)))
    )

    override suspend fun get(key: String) =
        map[key]?.let { item ->
            item.value.takeIf { item.expireAtMillis == null || item.expireAtMillis < System.currentTimeMillis() }
        }

    override suspend fun set(key: String, value: String, expireSec: Long?) {
        if (expireSec != null) {
            map[key] = Item(value, System.currentTimeMillis() + expireSec * 1_000)
        } else {
            map[key] = Item(value)
        }
    }

    override fun close() {}
}

@Suppress("unused")
fun provideFakeCache(): Cache = FakeCache()
