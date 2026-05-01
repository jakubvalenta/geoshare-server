package net.geoshare_app

import io.ktor.server.plugins.di.annotations.Property
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.RedisClient
import io.lettuce.core.SetArgs
import io.lettuce.core.api.coroutines

interface Cache : AutoCloseable {
    suspend fun get(key: String): String?
    suspend fun set(key: String, value: String, expireSec: Long? = null)
}

@OptIn(ExperimentalLettuceCoroutinesApi::class)
class CacheImpl(connectionUri: String) : Cache {
    private val client = RedisClient.create(connectionUri)
    private val connection = client.connect()
    private val commands = connection.coroutines()

    override suspend fun get(key: String) =
        commands.get(key)

    override suspend fun set(key: String, value: String, expireSec: Long?) {
        if (expireSec != null) {
            commands.set(key, value, SetArgs.Builder.ex(expireSec))
        } else {
            commands.set(key, value)
        }
    }

    override fun close() {
        connection.close()
        client.shutdown()
    }
}

@Suppress("unused")
fun provideCache(@Property("cache.uri") cacheUri: String): Cache =
    CacheImpl(cacheUri)
