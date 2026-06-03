package net.geoshare_app.testing

import io.ktor.server.plugins.NotFoundException
import kotlinx.serialization.SerializationException
import net.geoshare_app.GoogleMapsClient
import net.geoshare_app.GoogleMapsLocation
import net.geoshare_app.GoogleMapsNotFoundException
import net.geoshare_app.GoogleMapsResult
import net.geoshare_app.GoogleMapsResults

class FakeGoogleMapsClient : GoogleMapsClient {
    override suspend fun geocodeAddress(apiKey: String, query: String) =
        when (query) {
            CORRECT -> GoogleMapsResults(
                listOf(
                    GoogleMapsResult(GoogleMapsLocation(50.12345, -11.12345)),
                    GoogleMapsResult(GoogleMapsLocation(9.0, -120.0)),
                )
            )
            INVALID_RESPONSE -> throw GoogleMapsNotFoundException(
                "Client request exception",
                SerializationException()
            )
            NOT_FOUND -> throw GoogleMapsNotFoundException("Not found", NotFoundException())
            else -> throw NotImplementedError()
        }

    override suspend fun geocodePlace(apiKey: String, id: String) =
        when (id) {
            CORRECT -> GoogleMapsResult(GoogleMapsLocation(50.12345, -11.12345))
            INVALID_RESPONSE -> throw GoogleMapsNotFoundException(
                "Client request exception",
                SerializationException()
            )
            NOT_FOUND -> throw GoogleMapsNotFoundException("Not found", NotFoundException())
            else -> throw NotImplementedError()
        }

    companion object {
        const val CORRECT = "correct"
        const val INVALID_RESPONSE = "invalid-response"
        const val NOT_FOUND = "not-found"
    }
}
