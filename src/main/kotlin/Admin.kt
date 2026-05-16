package net.geoshare_app

import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

@Suppress("unused")
fun Application.adminModule(certificateRevocation: CertificateRevocation) {
    routing {
        authenticate { // TODO Authentication
            post("/v1/admin/revoked-serials/refresh") {
                certificateRevocation.refreshRevokedSerials()
            }
        }
    }
}
