package net.geoshare_app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.Application
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
object ApiKeys : Table("api_keys") {
    val id = uuid("id").autoGenerate()
    val name = text("name")
    val keyHash = text("key_hash").uniqueIndex()
    val keyPrefix = char("key_prefix", 8)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at").nullable()
    val revokedAt = long("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

fun Application.configureDatabase() {
    val config = HikariConfig().apply {
        jdbcUrl = environment.config.property("db.url").getString()
        driverClassName = "org.postgresql.Driver"
        username = environment.config.property("db.user").getString()
        password = environment.config.property("db.password").getString()
        maximumPoolSize = 10
    }
    val dataSource = HikariDataSource(config)

    Database.connect(dataSource)

    // Create tables if they don't exist
    transaction {
        SchemaUtils.create(ApiKeys)
    }
}
