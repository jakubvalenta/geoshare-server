package net.geoshare_app.lib

sealed class UpstreamException(cause: Throwable) : Exception(cause)

class UpstreamNotFoundException(cause: Throwable) : UpstreamException(cause)

class UpstreamUnauthorizedException(cause: Throwable) : UpstreamException(cause)

class UpstreamUnknownException(cause: Throwable) : UpstreamException(cause)
