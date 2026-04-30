package net.geoshare_app

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

class ServerTest {

    @BeforeTest
    fun setupDatabase() {
        Database.connect(
            url = @Suppress("SpellCheckingInspection") "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.create(ApiKeys)
        }
    }

    @AfterTest
    fun teardownDatabase() {
        transaction {
            SchemaUtils.drop(ApiKeys)
        }
    }

    @Test
    fun `root route returns 404`() = testApplication {
        configure("application-test.conf")
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/").status,
        )
    }

    @Test
    fun `geocode place id route when unknown api key is passed returns 401`() = testApplication {
        configure("application-test.conf")
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/google-maps/geocode/places/id").status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/google-maps/geocode/places/id") {
                headers["X-Api-Key"] = "spam"
            }.status,
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun `geocode place id route when correct api key is passed returns 200`() = testApplication {
        configure("application-test.conf")
        transaction {
            ApiKeys.insert {
                it[name] = "test"
                it[keyHash] = "test".sha256Hex()
                it[createdAt] = System.currentTimeMillis()
            }
        }
        assertEquals(
            HttpStatusCode.OK,
            client.get("/google-maps/geocode/places/id") {
                headers["X-Api-Key"] = "test"
            }.status,
        )
    }
}
