package net.geoshare_app.testing

import kotlinx.serialization.json.Json
import net.geoshare_app.Cache
import net.geoshare_app.Location
import net.geoshare_app.sha256Hex
import kotlin.time.Duration

class FakeCache : Cache {
    private data class Item(val value: String, val expireAtMillis: Long? = null)

    private val map: MutableMap<String, Item> = mutableMapOf(
        FakeGoogleMapsClient.NOT_FOUND_CACHED_PLACE_ID.sha256Hex() to
            Item(Json.encodeToString(Location(22.22, 111.11)))
    )

    override suspend fun get(key: String) =
        map[key]?.let { item ->
            item.value.takeIf { item.expireAtMillis == null || item.expireAtMillis > System.currentTimeMillis() }
        }

    override suspend fun set(key: String, value: String, expire: Duration) {
        map[key] = Item(value, System.currentTimeMillis() + expire.inWholeMilliseconds)
    }

    override suspend fun delete(key: String) {
        map.remove(key)
    }

    override suspend fun expire(key: String, expire: Duration) {
        map[key]?.let { item ->
            map[key] = item.copy(expireAtMillis = expire.inWholeMilliseconds)
        }
    }

    override fun close() {}
}

@Suppress("unused")
fun provideFakeCache(): Cache = FakeCache()
