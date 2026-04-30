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
    val name = varchar("name", 64)
    val keyHash = char("key_hash", 64).uniqueIndex()
    val createdAt = long("created_at")
    val expiresAt = long("expires_at").nullable()
    val revokedAt = long("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

@Suppress("unused")
fun Application.configureDatabase() {
    // TODO org.postgresql.Driver
    val config = HikariConfig().apply {
        jdbcUrl = environment.config.property("db.url").getString()
        driverClassName = environment.config.property("db.driver").getString()
        username = environment.config.propertyOrNull("db.user")?.getString()
        password = environment.config.propertyOrNull("db.password")?.getString()
        maximumPoolSize = 10
    }
    val dataSource = HikariDataSource(config)

    Database.connect(dataSource)

    // Create tables if they don't exist
    transaction {
        SchemaUtils.create(ApiKeys)
    }
}
