/*
 * Copyright (c) 2026 AtLarge Research
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.opendc.web.server.rest

import jakarta.validation.ConstraintViolationException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.jboss.resteasy.reactive.RestResponse
import org.jboss.resteasy.reactive.server.ServerExceptionMapper
import org.opendc.sdk.model.validation.ValidationIssue
import java.util.UUID

/**
 * The error envelope every endpoint returns, mirroring the frontend's ApiError contract. Each
 * [DocumentIssue] points into the submitted document using sdk-model validation paths.
 */
@Serializable
data class ApiProblem(
    val status: Int,
    val title: String,
    val detail: String? = null,
    val issues: List<DocumentIssue> = emptyList(),
)

@Serializable
data class DocumentIssue(
    val path: String,
    val message: String,
)

fun List<ValidationIssue>.toWire(): List<DocumentIssue> = map { DocumentIssue(it.path, it.message) }

/** A user document failed to parse or validate; carries the full problem to return. */
class InvalidDocumentException(val problem: ApiProblem) : RuntimeException(problem.title)

fun invalidDocument(
    title: String,
    issues: List<DocumentIssue>,
): InvalidDocumentException = InvalidDocumentException(ApiProblem(status = 400, title = title, issues = issues))

/**
 * Reads a public identifier from a request. Something that is not a well-formed id cannot name
 * anything, so it is a miss rather than a server error, and it reports exactly what a real id
 * belonging to someone else reports: callers learn nothing from the difference.
 */
fun publicId(
    raw: String,
    what: String,
): UUID =
    try {
        UUID.fromString(raw)
    } catch (e: IllegalArgumentException) {
        throw notFound(what)
    }

// Every name column in the schema is varchar(255). Without a cap here an over-long name reaches
// the database and comes back as a 500, when it is the caller's input that was wrong.
private const val NAME_LENGTH_CAP = 255

/** Reads a name a caller chose, trimmed, or reports what is wrong with it. */
fun validName(
    raw: String,
    what: String,
): String {
    val name = raw.trim()
    if (name.isEmpty()) {
        throw invalidDocument("$what name must not be blank", listOf(DocumentIssue("name", "must not be blank")))
    }
    if (name.length > NAME_LENGTH_CAP) {
        throw invalidDocument(
            "$what name is too long",
            listOf(DocumentIssue("name", "must be at most $NAME_LENGTH_CAP characters")),
        )
    }
    return name
}

fun notAuthenticated(): WebApplicationException =
    WebApplicationException(
        Response
            .status(401)
            .entity(ApiProblem(status = 401, title = "Sign in to continue"))
            .type(MediaType.APPLICATION_JSON)
            .build(),
    )

/**
 * Refuses a caller who can already see the thing they are acting on. Hiding it behind a 404 the way
 * an unrelated project is hidden would be theatre: they just read it, so the only thing left to
 * report is that their role does not stretch this far.
 */
fun forbidden(title: String): WebApplicationException =
    WebApplicationException(
        Response.status(403).entity(ApiProblem(status = 403, title = title)).type(MediaType.APPLICATION_JSON).build(),
    )

fun notFound(what: String): WebApplicationException =
    WebApplicationException(
        Response.status(404).entity(ApiProblem(status = 404, title = "$what not found")).type(MediaType.APPLICATION_JSON).build(),
    )

fun conflict(title: String): WebApplicationException =
    WebApplicationException(
        Response.status(409).entity(ApiProblem(status = 409, title = title)).type(MediaType.APPLICATION_JSON).build(),
    )

class ApiExceptionMappers {
    @ServerExceptionMapper
    fun invalidDocument(exception: InvalidDocumentException): RestResponse<ApiProblem> = problemResponse(exception.problem)

    // SerializationException extends IllegalArgumentException; map the specific type only, so
    // genuine server bugs keep surfacing as 500s.
    @ServerExceptionMapper
    fun malformedBody(exception: SerializationException): RestResponse<ApiProblem> =
        problemResponse(
            ApiProblem(
                status = 400,
                title = "Request body is not valid JSON for this endpoint",
                issues = listOf(DocumentIssue("", exception.message ?: "malformed body")),
            ),
        )

    @ServerExceptionMapper
    fun constraintViolation(exception: ConstraintViolationException): RestResponse<ApiProblem> =
        problemResponse(
            ApiProblem(
                status = 400,
                title = "Request validation failed",
                issues =
                    exception.constraintViolations.map {
                        DocumentIssue(it.propertyPath.toString(), it.message)
                    },
            ),
        )

    @ServerExceptionMapper
    fun webApplication(exception: WebApplicationException): RestResponse<ApiProblem> {
        val existing = exception.response.entity
        if (existing is ApiProblem) {
            return problemResponse(existing)
        }
        return problemResponse(
            ApiProblem(
                status = exception.response.status,
                title = exception.message ?: "Request failed",
            ),
        )
    }

    private fun problemResponse(problem: ApiProblem): RestResponse<ApiProblem> =
        RestResponse.ResponseBuilder
            .create<ApiProblem>(problem.status)
            .entity(problem)
            .type(MediaType.APPLICATION_JSON)
            .build()
}
