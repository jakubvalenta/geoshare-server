package net.geoshare_app

import io.ktor.server.plugins.NotFoundException
import kotlinx.serialization.SerializationException

class FakeGoogleMapsClient : GoogleMapsClient {
    override suspend fun geocode(googleMapsApiKey: String, placeId: String) =
        when (placeId) {
            CORRECT_PLACE_ID -> Location(50.12345, -11.12345)
            INVALID_RESPONSE_PLACE_ID -> throw GoogleMapsResponseException(SerializationException())
            NOT_FOUND_PLACE_ID -> throw NotFoundException()
            NOT_FOUND_CACHED_PLACE_ID -> throw NotFoundException()
            else -> throw NotImplementedError()
        }

    companion object {
        const val CORRECT_PLACE_ID = "correct"
        const val INVALID_RESPONSE_PLACE_ID = "invalid-response"
        const val NOT_FOUND_PLACE_ID = "not-found"
        const val NOT_FOUND_CACHED_PLACE_ID = "not-found-cached"
    }
}

@Suppress("unused")
fun provideFakeGoogleMapsClient(): GoogleMapsClient = FakeGoogleMapsClient()
