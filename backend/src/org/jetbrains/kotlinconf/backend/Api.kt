package org.jetbrains.kotlinconf.backend

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.date.*
import org.jetbrains.kotlinconf.*
import java.time.*
import java.time.format.*
import java.util.*

private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

internal fun Routing.api(
    database: Database,
    sessionizeUrl: String,
    adminSecret: String
) {
    apiUsers(database)
    apiAll(database)
    apiVote(database, adminSecret)
    apiFavorite(database)
    apiSynchronize(sessionizeUrl, adminSecret)
    apiTwitter()
    apiTime(adminSecret)
    apiLive(adminSecret)
}

/*
POST http://localhost:8080/user
1238476512873162837
 */
private fun Routing.apiUsers(database: Database) {
    route("users") {
        post {
            val userUUID = call.receive<String>()
            val ip = call.request.origin.remoteHost
            val timestamp = LocalDateTime.now(Clock.systemUTC())
            val created = database.createUser(userUUID, ip, timestamp)
            if (created)
                call.respond(HttpStatusCode.Created)
            else
                call.respond(HttpStatusCode.Conflict)
        }
        get("count") {
            call.respondText(database.usersCount().toString())
        }
    }
}

/*
GET http://localhost:8080/favorites
Accept: application/json
Authorization: Bearer 1238476512873162837
*/
private fun Routing.apiFavorite(database: Database) {
    route("favorites") {
        get {
            val principal = call.validatePrincipal(database) ?: throw Unauthorized()
            val favorites = database.getFavorites(principal.token)
            call.respond(favorites)
        }
        post {
            val principal = call.validatePrincipal(database) ?: throw Unauthorized()
            val sessionId = call.receive<String>()
            database.createFavorite(principal.token, sessionId)
            call.respond(HttpStatusCode.Created)
        }
        delete {
            val principal = call.validatePrincipal(database) ?: throw Unauthorized()
            val sessionId = call.receive<String>()
            database.deleteFavorite(principal.token, sessionId)
            call.respond(HttpStatusCode.OK)
        }
    }
}

/*
GET http://localhost:8080/votes
Accept: application/json
Authorization: Bearer 1238476512873162837
*/
private fun Routing.apiVote(
    database: Database,
    adminSecret: String
) {
    route("votes") {
        get {
            val principal = call.validatePrincipal(database) ?: throw Unauthorized()
            val votes = database.getVotes(principal.token)
            call.respond(votes)
        }
        get("all") {
            call.validateSecret(adminSecret)

            val votes = database.getAllVotes()
            call.respond(votes)
        }
        get("summary/{sessionId}") {
            call.validateSecret(adminSecret)

            val id = call.parameters["sessionId"] ?: throw BadRequest()
            val votesSummary = database.getVotesSummary(id)
            call.respond(votesSummary)
        }
        post {
            val principal = call.validatePrincipal(database) ?: throw Unauthorized()
            val vote = call.receive<VoteData>()
            val sessionId = vote.sessionId
            val rating = vote.rating!!.value

            val session = getSessionizeData().sessions.firstOrNull { it.id == sessionId } ?: throw NotFound()
            val nowTime = now()

            val startVotesAt = session.startsAt
            val votingPeriodStarted = nowTime >= startVotesAt

            if (!votingPeriodStarted) {
                return@post call.respond(comeBackLater)
            }

            val timestamp = LocalDateTime.now(Clock.systemUTC())
            val status = if (database.changeVote(principal.token, sessionId, rating, timestamp)) {
                HttpStatusCode.Created
            } else {
                HttpStatusCode.OK
            }

            call.respond(status)
        }
        delete {
            val principal = call.validatePrincipal(database) ?: throw Unauthorized()
            val vote = call.receive<VoteData>()
            val sessionId = vote.sessionId
            database.deleteVote(principal.token, sessionId)
            call.respond(HttpStatusCode.OK)
        }
    }
}

/*
GET http://localhost:8080/all
Accept: application/json
Authorization: Bearer 1238476512873162837
*/
private fun Routing.apiAll(database: Database) {
    get("all") {
        val data = getSessionizeData()
        val principal = call.validatePrincipal(database)
        val responseData = if (principal != null) {
            val votes = database.getVotes(principal.token)
            val favorites = database.getFavorites(principal.token)
            ConferenceData(data, favorites, votes, liveInfo())
        } else {
            ConferenceData(data, liveVideos = liveInfo())
        }

        call.respond(responseData)
    }
}

private fun Routing.apiTwitter() {
    get("feed") {
        call.respond(getFeedData())
    }
}

private fun Routing.apiTime(adminSecret: String) {
    get("time") {
        call.respond(now().timestamp)
    }
    post("time/{timestamp}") {
        call.validateSecret(adminSecret)

        val timestamp = call.parameters["timestamp"] ?: error("No time")
        val time = if (timestamp == "null") {
            null
        } else {
            GMTDate(timestamp.toLong())
        }

        updateTime(time)
        call.respond(HttpStatusCode.OK)
    }
}

private fun Routing.apiLive(adminSecret: String) {
    post("live") {
        call.validateSecret(adminSecret)

        val form = call.receiveParameters()
        val room = form["roomId"]?.toIntOrNull() ?: throw BadRequest()
        val video = form["video"]

        addLive(room, video)
        call.respond(HttpStatusCode.OK)
    }
}

/*
GET http://localhost:8080/sessionizeSync
*/
private fun Routing.apiSynchronize(sessionizeUrl: String, adminSecret: String) {
    post("sessionizeSync") {
        call.validateSecret(adminSecret)

        synchronizeWithSessionize(sessionizeUrl)
        call.respond(HttpStatusCode.OK)
    }
}

private fun ApplicationCall.validateSecret(adminSecret: String) {
    val principal = principal<KotlinConfPrincipal>()
    if (principal?.token != adminSecret) {
        throw Unauthorized()
    }
}

private suspend fun ApplicationCall.validatePrincipal(database: Database): KotlinConfPrincipal? {
    val principal = principal<KotlinConfPrincipal>() ?: return null
    if (!database.validateUser(principal.token)) return null
    return principal
}

