package net.geoshare_app

import kotlin.time.Duration.Companion.hours

class StatsRepository(private val cache: Cache) {
    suspend fun increase(key: String) {
        cache.increase(key, expire)
    }

    suspend fun hashIncrease(key: String, field: String) {
        cache.hashIncrease(key, field, expire)
    }

    suspend fun get(key: String): Int =
        cache.get(key)?.toIntOrNull() ?: 0

    suspend fun hashGet(key: String, field: String): Int =
        cache.hashGet(key, field)?.toIntOrNull() ?: 0

    private companion object {
        private val expire = 168.hours
    }
}

@Suppress("unused")
fun provideStatsRepository(cache: Cache): StatsRepository =
    StatsRepository(cache)
