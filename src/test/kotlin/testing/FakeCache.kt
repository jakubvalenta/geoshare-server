package net.geoshare_app.testing

import net.geoshare_app.Cache
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class FakeCache(private val timeSource: TimeSource = TimeSource.Monotonic) : Cache {
    private data class Item(val value: String, val timeMark: TimeMark? = null, val expire: Duration? = null)

    private val map: MutableMap<String, Item> = mutableMapOf()

    override suspend fun get(key: String) =
        map[key]?.let { item ->
            item.value.takeIf {
                item.timeMark == null || item.expire == null || item.timeMark.elapsedNow() < item.expire
            }
        }

    override suspend fun set(key: String, value: String) {
        map[key] = Item(value)
    }

    override suspend fun set(key: String, value: String, expire: Duration) {
        map[key] = Item(value, timeSource.markNow(), expire)
    }

    override suspend fun delete(key: String) {
        map.remove(key)
    }

    override suspend fun expire(key: String, expire: Duration) {
        map[key]?.let { item ->
            map[key] = item.copy(timeMark = timeSource.markNow(), expire = expire)
        }
    }

    override suspend fun ping() = true

    override fun close() {}
}
