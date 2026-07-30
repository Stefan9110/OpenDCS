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

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.opendc.web.server.model.Trace
import org.opendc.web.server.model.TraceGrant
import org.opendc.web.server.model.TraceKind
import org.opendc.web.server.model.TracePart
import org.opendc.web.server.service.Identity

@Serializable
enum class TraceKindWire {
    @SerialName("workload")
    WORKLOAD,

    @SerialName("carbon")
    CARBON,

    @SerialName("failure")
    FAILURE,
}

/**
 * How the caller came by a trace, which is what tells the library which rows it may change: a
 * built-in belongs to the deployment, and one shared with you belongs to somebody else.
 */
@Serializable
enum class TraceAccessWire {
    @SerialName("builtin")
    BUILTIN,

    @SerialName("owned")
    OWNED,

    @SerialName("shared")
    SHARED,
}

// The slug is the name an experiment references, and the public id is what this API addresses. The
// two are separate because the slug may be corrected while nothing points at it, and an identifier
// that can change cannot be the one in URLs.
@Serializable
data class TraceRow(
    val id: String,
    val slug: String,
    val kind: TraceKindWire,
    val access: TraceAccessWire,
    val description: String? = null,
    val sizeBytes: Long = 0,
    val taskCount: Long? = null,
    val fragmentCount: Long? = null,
    val timeSpanSeconds: Double? = null,
    val createdAt: String,
)

@Path("traces")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class TracesResource(
    private val identity: Identity,
) {
    /** The caller's library: what the deployment ships, what they uploaded, what others shared. */
    @GET
    fun list(
        @QueryParam("kind") kind: String?,
    ): List<TraceRow> {
        val wanted = kind?.let(::traceKind)
        val user = identity.currentUser()
        val rows =
            Trace.findBuiltIns().map { it to TraceAccessWire.BUILTIN } +
                Trace.findOwnedBy(user.id).map { it to TraceAccessWire.OWNED } +
                TraceGrant.findSharedWith(user.id).map { it.trace to TraceAccessWire.SHARED }
        return rows
            .filter { (trace, _) -> wanted == null || trace.kind == wanted }
            .sortedBy { (trace, _) -> trace.slug }
            .map { (trace, access) -> trace.toRow(access) }
    }
}

// A kind nobody serves is a mistake in the request, not an empty library: answering [] would tell a
// caller who misspelled "carbon" that they simply have no carbon traces.
private fun traceKind(raw: String): TraceKind =
    when (raw) {
        "workload" -> TraceKind.WORKLOAD
        "carbon" -> TraceKind.CARBON
        "failure" -> TraceKind.FAILURE
        else ->
            throw invalidDocument(
                "Unknown trace kind",
                listOf(DocumentIssue("kind", "must be one of workload, carbon, failure")),
            )
    }

// Size is the sum of the trace's parts rather than a column of its own: a built-in has no parts at
// all, and a stored total would be one more thing to keep true when they change.
private fun Trace.toRow(access: TraceAccessWire): TraceRow =
    TraceRow(
        id = publicId.toString(),
        slug = slug,
        kind = kind.toWire(),
        access = access,
        description = description,
        sizeBytes = TracePart.findByTrace(id).sumOf { it.sizeBytes },
        taskCount = taskCount,
        fragmentCount = fragmentCount,
        timeSpanSeconds = timeSpanSeconds,
        createdAt = createdAt.toString(),
    )

private fun TraceKind.toWire(): TraceKindWire =
    when (this) {
        TraceKind.WORKLOAD -> TraceKindWire.WORKLOAD
        TraceKind.CARBON -> TraceKindWire.CARBON
        TraceKind.FAILURE -> TraceKindWire.FAILURE
    }
