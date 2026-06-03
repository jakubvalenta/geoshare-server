package net.geoshare_app

import java.math.RoundingMode
import kotlin.random.Random

class DemoGoogleMapsClient : GoogleMapsClient {
    override suspend fun geocodeAddress(apiKey: String, query: String) =
        GoogleMapsResults(listOf(GoogleMapsResult(genRandomLocation())))

    override suspend fun geocodePlace(apiKey: String, placeId: String) =
        GoogleMapsResult(genRandomLocation())
}

private fun genRandomLocation(
    minLat: Double = -50.0,
    maxLat: Double = 80.0,
    minLon: Double = -180.0,
    maxLon: Double = 180.0,
): GoogleMapsLocation = GoogleMapsLocation(
    Random.nextDouble(minLat, maxLat)
        .toBigDecimal().setScale(6, RoundingMode.HALF_UP).toDouble(),
    Random.nextDouble(minLon, maxLon)
        .toBigDecimal().setScale(6, RoundingMode.HALF_UP).toDouble(),
)

@Suppress("unused")
fun provideDemoGoogleMapsClient(): GoogleMapsClient = DemoGoogleMapsClient()
