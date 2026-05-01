package net.geoshare_app

class FakeCache : Cache {
    private data class Item(val value: String, val expireAtMillis: Long? = null)

    private val map: MutableMap<String, Item> = mutableMapOf()

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
