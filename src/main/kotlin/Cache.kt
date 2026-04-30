package net.geoshare_app

import io.ktor.server.plugins.di.annotations.Property
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.RedisClient
import io.lettuce.core.api.coroutines

@OptIn(ExperimentalLettuceCoroutinesApi::class)
class Cache(connectionUri: String) : AutoCloseable {
    private val client = RedisClient.create(connectionUri)
    private val connection = client.connect()
    private val commands = connection.coroutines()

    suspend fun get(key: String): String? =
        commands.get(key)

    suspend fun set(key: String, value: String) {
        commands.set(key, value)
    }

    override fun close() {
        connection.close()
        client.shutdown()
    }
}

@Suppress("unused")
fun provideCache(@Property("cache.uri") cacheUri: String): Cache =
    Cache(cacheUri)
