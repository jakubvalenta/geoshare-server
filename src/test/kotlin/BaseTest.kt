package net.geoshare_app

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

interface BaseTest {

    @BeforeTest
    fun setupDatabase() {
        Database.connect(
            url = @Suppress("SpellCheckingInspection") "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.create(ApiKeys)
            ApiKeys.insert {
                it[name] = CORRECT_API_KEY
                it[keyHash] = CORRECT_API_KEY.sha256Hex()
                it[createdAt] = System.currentTimeMillis()
            }
        }
    }

    @AfterTest
    fun teardownDatabase() {
        transaction {
            SchemaUtils.drop(ApiKeys)
        }
    }

    companion object {
        const val CORRECT_API_KEY = "test"
        const val UNKNOWN_API_KEY = "spam"
    }
}
