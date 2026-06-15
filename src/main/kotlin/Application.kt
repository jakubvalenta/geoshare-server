package net.geoshare_app

import io.ktor.client.engine.HttpClientEngine
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
fun Application.rootModule(
    cache: Cache,
    certificateVerification: CertificateVerification,
    engine: HttpClientEngine,
    statsRepository: StatsRepository,
) {
    install(ContentNegotiation) { json() }
    install(Resources)

    rateLimitModule()
    authenticationModule(certificateVerification)
    statusPagesModule(statsRepository)

    routing {
        authenticationRoutes(cache, certificateVerification, statsRepository)
        cacheRoutes(cache)
        googleMapsRoutes(engine, statsRepository)
        rateLimitRoutes(statsRepository)
    }
}

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)
