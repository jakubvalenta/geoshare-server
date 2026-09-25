@file:OptIn(ExperimentalKtorApi::class)

package net.geoshare_app

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticateWith
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.serialization.Serializable
import net.geoshare_app.lib.StatusFailed
import net.geoshare_app.lib.base64Decode
import net.geoshare_app.lib.base64Encode
import net.geoshare_app.lib.details
import net.geoshare_app.lib.formatHour
import net.geoshare_app.lib.listHours
import java.time.LocalDateTime

@Serializable
data class ChallengeResponse(val challenge: String)

@Serializable
data class RegisterRequest(val challenge: String, val signature: String, val certificateChain: List<String>)

@Serializable
data class LoginRequest(val challenge: String, val signature: String, val publicKey: String)

@Serializable
data class TokenResponse(val token: String)

/**
 * See [Authentication] for a description of the authentication flow.
 */
fun Route.authenticationRoutes(authentication: Authentication, statsRepository: StatsRepository) {
    rateLimit {
        post("/v1/auth/challenge") {
            val challenge = authentication.generateChallenge()
            call.respond(ChallengeResponse(challenge.base64Encode()))
        }
    }

    rateLimit(RateLimitName("register")) {
        post("/v1/auth/register") {
            val req = call.receive<RegisterRequest>()
            when (val res = authentication.register(
                certificateChain = req.certificateChain,
                challengeBase64 = req.challenge,
                signature = req.signature.base64Decode(),
            )) {
                is RegistrationResult.Conflict -> call.respond(HttpStatusCode.Conflict, res.message)
                is RegistrationResult.Success -> call.respond(TokenResponse(res.token))
                is RegistrationResult.Unauthorized -> call.respond(HttpStatusCode.Unauthorized, res.message)
            }
        }
    }

    rateLimit {
        post("/v1/auth/login") {
            val req = call.receive<LoginRequest>()
            when (val res = authentication.login(
                challengeBase64 = req.challenge,
                publicKey = req.publicKey.base64Decode(),
                signature = req.signature.base64Decode(),
            )) {
                is LoginResult.Success -> call.respond(TokenResponse(res.token))
                is LoginResult.Unauthorized -> call.respond(HttpStatusCode.Unauthorized, res.message)
            }
        }
    }

    route("/v1/status/auth") {
        rateLimit {
            authenticateWith(authentication.statusScheme) {
                head("/challenge/success/hour") {
                    // Check that there hasn't been too many challenge requests, which would suggest misuse
                    with(call.details) {
                        val num = statsRepository.get("stats:auth:challenge:success:$hour:total")
                        if (num > 100) {
                            call.respond(StatusFailed, num)
                        } else {
                            call.respond(num)
                        }
                    }
                }
                head("/legacy-signature/success/7days") {
                    // Check that there haven't been any legacy signature uses, so we can disable legacy signatures
                    val num = LocalDateTime.now()
                        .listHours(0 downTo -167L)
                        .sumOf {
                            statsRepository.get("stats:auth:legacy-signature:success:${formatHour(it)}:total")
                        }
                    if (num > 0) {
                        call.respond(StatusFailed, num)
                    } else {
                        call.respond(num)
                    }
                }
                head("/login/success/hour") {
                    // Check that there hasn't been too many successful logins, which would suggest misuse
                    with(call.details) {
                        val num = statsRepository.get("stats:auth:login:success:$hour:total")
                        if (num > 10) {
                            call.respond(StatusFailed, num)
                        } else {
                            call.respond(num)
                        }
                    }
                }
                head("/login/error/hour") {
                    // Check that there hasn't been too many failed logins
                    with(call.details) {
                        val num = statsRepository.get("stats:auth:login:error:$hour:total")
                        if (num > 10) {
                            call.respond(StatusFailed, num)
                        } else {
                            call.respond(num)
                        }
                    }
                }
                head("/register/success/hour") {
                    // Check that there hasn't been too many successful registrations, which would suggest misuse
                    with(call.details) {
                        val num = statsRepository.get("stats:auth:register:success:$hour:total")
                        if (num > 10) {
                            call.respond(StatusFailed, num)
                        } else {
                            call.respond(num)
                        }
                    }
                }
                head("/register/error/hour") {
                    // Check that there hasn't been too many failed registrations
                    with(call.details) {
                        val num = statsRepository.get("stats:auth:register:error:$hour:total")
                        if (num > 10) {
                            call.respond(StatusFailed, num)
                        } else {
                            call.respond(num)
                        }
                    }
                }
                head("/unauthorized/hour") {
                    // Check that there hasn't been too many unauthorized requests
                    with(call.details) {
                        val num = statsRepository.get("stats:auth:unauthorized:$hour:total")
                        if (num > 100) {
                            call.respond(StatusFailed, num)
                        } else {
                            call.respond(num)
                        }
                    }
                }
            }
        }
    }
}
