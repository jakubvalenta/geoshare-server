package net.geoshare_app.testing

import net.geoshare_app.Cache
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private sealed interface Item<T> {
    val value: T
    val timeMark: TimeMark?
    val expire: Duration?
}

private data class StringItem(
    override val value: String,
    override val timeMark: TimeMark? = null,
    override val expire: Duration? = null
) : Item<String>

private data class IntItem(
    @Suppress("SameParameterValue") override val value: Int,
    override val timeMark: TimeMark? = null,
    override val expire: Duration? = null
) : Item<Int>

private data class HashItem(
    override val value: MutableMap<String, Int>,
    override val timeMark: TimeMark? = null,
    override val expire: Duration? = null
) : Item<Map<String, Int>>

class FakeCache(private val timeSource: TimeSource = TimeSource.Monotonic) : Cache {
    private val map: MutableMap<String, Item<*>> = mutableMapOf()

    override suspend fun get(key: String) =
        map[key]?.let { item ->
            (item as StringItem).value
                .takeIf { item.timeMark == null || item.expire == null || item.timeMark.elapsedNow() < item.expire }
        }

    override suspend fun hashGet(key: String, field: String): String? =
        map[key]?.let { item ->
            (item as HashItem).value[field]?.toString()
        }

    override suspend fun set(key: String, value: String) {
        map[key] = StringItem(value)
    }

    override suspend fun set(key: String, value: String, expire: Duration) {
        map[key] = StringItem(value, timeSource.markNow(), expire)
    }

    override suspend fun increase(key: String, expire: Duration) {
        if (map.contains(key)) {
            map[key] = (map[key] as IntItem).run { copy(value = value + 1) }
        } else {
            map[key] = IntItem(1, timeSource.markNow(), expire)
        }
    }

    override suspend fun hashIncrease(key: String, field: String, expire: Duration) {
        if (map.contains(key)) {
            val hash = (map[key] as HashItem).value
            if (hash.contains(field)) {
                hash[field]?.plus(1)
            } else {
                hash[field] = 1
            }
        } else {
            map[key] = HashItem(mutableMapOf(), timeSource.markNow(), expire)
        }
    }

    override suspend fun delete(key: String) {
        map.remove(key)
    }

    override suspend fun expire(key: String, expire: Duration) {
        map[key]?.let { item ->
            map[key] = when (item) {
                is HashItem -> item.copy(timeMark = timeSource.markNow(), expire = expire)
                is IntItem -> item.copy(timeMark = timeSource.markNow(), expire = expire)
                is StringItem -> item.copy(timeMark = timeSource.markNow(), expire = expire)
            }
        }
    }

    override suspend fun ping() = true

    override fun close() {}
}
