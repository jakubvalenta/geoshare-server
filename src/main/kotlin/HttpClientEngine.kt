package net.geoshare_app

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO

@Suppress("unused")
fun provideHttpClientEngine(): HttpClientEngine = CIO.create()
