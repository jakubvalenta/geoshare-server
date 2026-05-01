package net.geoshare_app

import io.ktor.server.plugins.NotFoundException

class FakeGoogleMapsClient : GoogleMapsClient {
    override suspend fun geocode(googleMapsApiKey: String, placeId: String) =
        when (placeId) {
            "test" -> Location(50.12345, -11.12345)
            else -> throw NotFoundException()
        }
}

@Suppress("unused")
fun provideFakeGoogleMapsClient(): GoogleMapsClient = FakeGoogleMapsClient()
