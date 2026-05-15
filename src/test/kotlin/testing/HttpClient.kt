package net.geoshare_app.testing

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder

val ApplicationTestBuilder.jsonClient: HttpClient
    get() = createClient {
        install(ContentNegotiation) {
            json()
        }
    }
