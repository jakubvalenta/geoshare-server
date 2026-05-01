package net.geoshare_app

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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
        val res = client.get("/")
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `geocode place id route when unknown api key is passed returns 401`() = testApplication {
        configure("application-test.conf")
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/google-maps/geocode/places/test").status,
        )
        val res = client.get("/google-maps/geocode/places/test") {
            headers["X-Api-Key"] = "spam"
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun `geocode place id route when correct api key is passed and google api returns 404 returns 404`() =
        testApplication {
            configure("application-test.conf")
            transaction {
                ApiKeys.insert {
                    it[name] = "test"
                    it[keyHash] = "test".sha256Hex()
                    it[createdAt] = System.currentTimeMillis()
                }
            }
            val res = client.get("/google-maps/geocode/places/test") {
                headers["X-Api-Key"] = "test"
            }
            assertEquals(HttpStatusCode.NotFound, res.status)
        }

    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun `geocode place id route when correct api key is passed and google api returns 200 returns 200`() =
        testApplication {
            configure("application-test.conf")
            transaction {
                ApiKeys.insert {
                    it[name] = "test"
                    it[keyHash] = "test".sha256Hex()
                    it[createdAt] = System.currentTimeMillis()
                }
            }
            val res = client.get("/google-maps/geocode/places/test") {
                headers["X-Api-Key"] = "test"
            }
            assertEquals(HttpStatusCode.OK, res.status)
            assertEquals("""{"latitude":50.12345,"longitude":-11.12345}""", res.bodyAsText())
        }
}
