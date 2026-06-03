package net.geoshare_app.testing

import io.ktor.server.plugins.NotFoundException
import net.geoshare_app.GoogleMapsClient
import net.geoshare_app.GoogleMapsLocation
import net.geoshare_app.GoogleMapsNotFoundException
import net.geoshare_app.GoogleMapsResult
import net.geoshare_app.GoogleMapsResults
import net.geoshare_app.GoogleMapsUnauthorizedException
import net.geoshare_app.GoogleMapsUnknownException
import java.net.SocketTimeoutException

class FakeGoogleMapsClient : GoogleMapsClient {
    override suspend fun geocodeAddress(apiKey: String, query: String) =
        when (query) {
            CORRECT -> GoogleMapsResults(
                listOf(
                    GoogleMapsResult(GoogleMapsLocation(50.123456, -11.123456)),
                    GoogleMapsResult(GoogleMapsLocation(9.0, -120.0)),
                )
            )

            EXCEPTION -> throw GoogleMapsUnknownException(SocketTimeoutException())
            NOT_FOUND -> throw GoogleMapsNotFoundException(NotFoundException())
            UNAUTHORIZED -> throw GoogleMapsUnauthorizedException(Exception())
            else -> throw NotImplementedError()
        }

    override suspend fun geocodePlace(apiKey: String, placeId: String) =
        when (placeId) {
            CORRECT -> GoogleMapsResult(GoogleMapsLocation(50.123456, -11.123456))
            EXCEPTION -> throw GoogleMapsUnknownException(SocketTimeoutException())
            NOT_FOUND -> throw GoogleMapsNotFoundException(NotFoundException())
            UNAUTHORIZED -> throw GoogleMapsUnauthorizedException(Exception())
            else -> throw NotImplementedError()
        }

    companion object {
        const val CORRECT = "correct"
        const val EXCEPTION = "exception"
        const val NOT_FOUND = "not-found"
        const val UNAUTHORIZED = "unauthorized"
    }
}
