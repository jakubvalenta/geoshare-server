package net.geoshare_app

import io.ktor.server.plugins.di.annotations.Property
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisException
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.coroutines
import kotlin.time.Duration

interface Cache : AutoCloseable {
    suspend fun get(key: String): String?

    suspend fun hashGet(key: String, field: String): String?

    suspend fun set(key: String, value: String)

    suspend fun set(key: String, value: String, expire: Duration)

    /**
     * Sets [key] to [value] with [expire] if the key didn't exist, and returns true if the key didn't exist.
     */
    suspend fun setIfNotExists(key: String, value: String, expire: Duration): Boolean

    suspend fun increase(key: String, expire: Duration)

    suspend fun hashIncrease(key: String, field: String, expire: Duration)

    /**
     * Deletes [key] and returns true if the key existed.
     */
    suspend fun delete(key: String): Boolean

    suspend fun expire(key: String, expire: Duration)

    suspend fun ping(): Boolean
}

@OptIn(ExperimentalLettuceCoroutinesApi::class)
class CacheImpl(connectionUri: String) : Cache {
    private val client = RedisClient.create(connectionUri)
    private val connection = client.connect()
    private val commands = connection.coroutines()

    /**
     * Script to atomically increase a key and set its expiration. Arguments: expire
     */
    private val increaseScript = connection.sync().scriptLoad(
        @Suppress("SpellCheckingInspection")
        // language=Lua
        """
        local current
            current = redis.call("incr", KEYS[1])
        if current == 1 then
            redis.call("expire", KEYS[1], ARGV[1])
        end
        """
    )

    /**
     * Script to atomically increase a hash and set its expiration. Arguments: field, amount, expire
     */
    private val hashIncreaseScript = connection.sync().scriptLoad(
        @Suppress("SpellCheckingInspection")
        // language=Lua
        """
        local current
            current = redis.call("hincrby", KEYS[1], ARGV[1], ARGV[2])
        if current == 1 then
            redis.call("expire", KEYS[1], ARGV[3])
        end
        """
    )

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

    override suspend fun setIfNotExists(key: String, value: String, expire: Duration): Boolean =
        commands.set(key, value, SetArgs.Builder.ex(expire.inWholeSeconds).nx()) != null

    override suspend fun increase(key: String, expire: Duration) {
        commands.evalsha<Any>(
            increaseScript,
            ScriptOutputType.STATUS,
            arrayOf(key),
            expire.inWholeMilliseconds.toString(),
        )
    }

    override suspend fun hashIncrease(key: String, field: String, expire: Duration) {
        commands.evalsha<Any>(
            hashIncreaseScript,
            ScriptOutputType.STATUS,
            arrayOf(key),
            field,
            "1",
            expire.inWholeMilliseconds.toString(),
        )
    }

    override suspend fun delete(key: String): Boolean {
        val removedNumber = commands.del(key)
        return removedNumber != null && removedNumber > 0
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
