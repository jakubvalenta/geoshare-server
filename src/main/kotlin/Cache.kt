package net.geoshare_app

import io.ktor.server.plugins.di.annotations.Property
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisException
import io.lettuce.core.SetArgs
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.multi
import kotlin.time.Duration

interface Cache : AutoCloseable {
    suspend fun get(key: String): String?
    suspend fun hashGet(key: String, field: String): String?
    suspend fun set(key: String, value: String)
    suspend fun set(key: String, value: String, expire: Duration)
    suspend fun increase(key: String, expire: Duration)
    suspend fun hashIncrease(key: String, field: String, expire: Duration)
    suspend fun delete(key: String)
    suspend fun expire(key: String, expire: Duration)
    suspend fun ping(): Boolean
}

@OptIn(ExperimentalLettuceCoroutinesApi::class)
class CacheImpl(connectionUri: String) : Cache {
    private val client = RedisClient.create(connectionUri)
    private val connection = client.connect()
    private val commands = connection.coroutines()

    override suspend fun get(key: String) =
        commands.get(key)

    override suspend fun hashGet(key: String, field: String) =
        commands.hget(key, field)

    override suspend fun set(key: String, value: String) {
        commands.set(key, value)
    }

    override suspend fun set(key: String, value: String, expire: Duration) {
        commands.set(key, value, SetArgs.Builder.ex(expire.inWholeSeconds))
    }

    override suspend fun increase(key: String, expire: Duration) {
        commands.multi {
            incr(key)
            expire(key, expire.inWholeSeconds)
        }
    }

    override suspend fun hashIncrease(key: String, field: String, expire: Duration) {
        commands.multi {
            hincrby(key, field, 1)
            expire(key, expire.inWholeSeconds)
        }
    }

    override suspend fun delete(key: String) {
        commands.del(key)
    }

    override suspend fun expire(key: String, expire: Duration) {
        commands.expire(key, expire.inWholeSeconds)
    }

    override suspend fun ping() =
        try {
            commands.ping()
            true
        } catch (_: RedisException) {
            false
        }

    override fun close() {
        connection.close()
        client.shutdown()
    }
}

@Suppress("unused")
fun provideCache(@Property("cache.uri") cacheUri: String): Cache =
    CacheImpl(cacheUri)
