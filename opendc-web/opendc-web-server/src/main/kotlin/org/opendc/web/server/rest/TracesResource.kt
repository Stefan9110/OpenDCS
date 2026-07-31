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

import jakarta.transaction.Transactional
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.StreamingOutput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.opendc.web.server.model.ExperimentResource
import org.opendc.web.server.model.Trace
import org.opendc.web.server.model.TraceGrant
import org.opendc.web.server.model.TraceKind
import org.opendc.web.server.model.TraceOrigin
import org.opendc.web.server.model.TracePart
import org.opendc.web.server.model.UserAccount
import org.opendc.web.server.service.Identity
import org.opendc.web.server.service.TraceDisposal
import org.opendc.web.server.service.TraceIngest
import org.opendc.web.server.storage.TraceStore
import org.opendc.web.server.storage.UploadTarget
import org.opendc.web.server.storage.traceKey
import java.io.InputStream
import java.time.Instant
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

/** One table of a trace, and how much of it arrived. */
@Serializable
data class TraceTable(
    val name: String,
    val sizeBytes: Long,
    /** Rows in the table, which is the one place "row" means what it means in a parquet file. */
    val rowCount: Long? = null,
)

// The slug is the name an experiment references, and the public id is what this API addresses. The
// two are separate because the slug may be corrected while nothing points at it, and an identifier
// that can change cannot be the one in URLs.
@Serializable
data class TraceWire(
    val id: String,
    val slug: String,
    val kind: TraceKindWire,
    val access: TraceAccessWire,
    val description: String? = null,
    val sizeBytes: Long,
    val tables: List<TraceTable>,
    val createdAt: String,
)

/** A kind of trace and the tables one is made of. */
@Serializable
data class TraceKindTables(
    val kind: TraceKindWire,
    val tables: List<String>,
)

/**
 * A file about to be sent, and how large it is.
 *
 * The size is given before a byte moves because that is when it is needed: how a file is cut into
 * parts, and therefore how many connections may carry it at once, is decided while the targets are
 * being handed out.
 */
@Serializable
data class UploadedFile(
    val table: String,
    val sizeBytes: Long,
)

@Serializable
data class TraceRegistration(
    val kind: String,
    val name: String,
    val description: String? = null,
    val files: List<UploadedFile> = emptyList(),
)

/** One stretch of a table's file and where it goes. A relative path means this server takes it. */
@Serializable
data class UploadPart(
    val url: String,
    val offset: Long,
    val length: Long,
)

/**
 * Where to send one table's bytes. More than one part means they may go at once, which is what
 * takes a large trace off the speed of a single connection.
 */
@Serializable
data class UploadSlot(
    val table: String,
    val parts: List<UploadPart>,
    val direct: Boolean,
)

@Serializable
data class RegisteredTrace(
    val trace: TraceWire,
    val uploads: List<UploadSlot>,
)

@Serializable
data class TraceEdit(
    val name: String? = null,
    val description: String? = null,
)

/** Somebody a trace has been shared with. */
@Serializable
data class TraceShare(
    val handle: String,
    val displayName: String,
)

@Serializable
data class ShareRequest(
    val handle: String,
)

@Path("traces")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class TracesResource(
    private val identity: Identity,
    private val store: TraceStore,
    private val ingest: TraceIngest,
    private val disposal: TraceDisposal,
) {
    /**
     * The caller's library: what the deployment ships, what they uploaded, what others shared.
     *
     * A trace whose upload never finished is not among them. Registering, uploading and completing
     * are three requests, but nobody outside the upload sees a trace between the first and the
     * last: it appears whole or not at all.
     */
    @GET
    fun list(
        @QueryParam("kind") kind: String?,
    ): List<TraceWire> {
        val user = identity.currentUser()
        val wanted = kind?.let(::traceKind)
        val found =
            (
                Trace.findBuiltIns().map { it to TraceAccessWire.BUILTIN } +
                    Trace.findOwnedBy(user.id).map { it to TraceAccessWire.OWNED } +
                    TraceGrant.findSharedWith(user.id).map { it.trace to TraceAccessWire.SHARED }
            ).filter { (trace, _) -> wanted == null || trace.kind == wanted }

        // One query for every trace's tables rather than one per trace, and it answers both what a
        // trace holds and whether it is finished.
        val tables = tablesOf(found.map { (trace, _) -> trace.id })
        return found
            .filter { (trace, _) -> tables.holdsAll(trace) }
            .sortedBy { (trace, _) -> trace.slug }
            .map { (trace, access) -> trace.toWire(access, tables[trace.id].orEmpty()) }
    }

    /**
     * What may be uploaded and which files each kind is made of, so an upload form does not have
     * to carry its own copy of that and fall out of step with the readers.
     */
    @GET
    @Path("kinds")
    fun kinds(): List<TraceKindTables> = TraceKind.entries.map { TraceKindTables(it.toWire(), it.tables) }

    @GET
    @Path("{id}")
    fun get(
        @PathParam("id") id: String,
    ): TraceWire {
        val trace = visible(id)
        return trace.toWire(accessTo(trace))
    }

    /**
     * Claims a name and hands back somewhere to put each table.
     *
     * The bytes do not come through here. Object storage can be written to from a browser, so a
     * trace of any size goes straight there and this server only ever sees the name of it; where
     * storage is a directory on a development machine there is nowhere to point at, so the slot
     * names an endpoint on this server instead.
     */
    @POST
    @Transactional
    fun register(request: TraceRegistration): Response {
        val kind = traceKind(request.kind)
        val owner = identity.currentUser()
        val slug = "${owner.handle}/${traceName(request.name)}"
        // Settled before anything is created: handing out a target for a large file opens an
        // upload in storage, and getting that far only to refuse the request would leave one open
        // that nothing is ever going to finish or clear away.
        val sizes = declaredSizes(kind, request.files)
        val existing = Trace.findBySlug(slug)
        if (existing != null) {
            // An upload that never finished holds a name nobody can see, so nothing would ever
            // free it. Since it is invisible and unusable, starting again simply takes it over.
            if (existing.owner?.id != owner.id || tablesOf(listOf(existing.id)).holdsAll(existing)) {
                throw conflict("You already have a trace called $slug")
            }
            disposal.discard(existing)
        }

        val now = Instant.now()
        val trace = Trace()
        trace.slug = slug
        trace.kind = kind
        trace.origin = TraceOrigin.UPLOADED
        trace.owner = owner
        trace.description = request.description.cleaned()
        trace.createdAt = now
        trace.updatedAt = now
        trace.persist()

        val uploads = kind.tables.map { table -> slotFor(trace, table, sizes.getValue(table)) }
        return Response.status(201).entity(RegisteredTrace(trace.toWire(TraceAccessWire.OWNED), uploads)).build()
    }

    /**
     * Takes one table's bytes, for a store a browser cannot write to directly.
     *
     * Any content type: this reads raw bytes and has no use for what the sender calls them, while a
     * browser handed a .parquet file names no type at all and would be turned away by a stricter
     * rule.
     */
    @PUT
    @Path("{id}/tables/{table}")
    @Consumes(MediaType.WILDCARD)
    fun putTable(
        @PathParam("id") id: String,
        @PathParam("table") table: String,
        body: InputStream,
    ): Response {
        val trace = owned(id)
        if (table !in trace.kind.tables) {
            throw notFound("Table $table")
        }
        body.use { store.put(traceKey(trace.publicId, table), it) }
        return Response.status(204).build()
    }

    /**
     * Checks that what was uploaded is what it was filed as, and makes the trace usable.
     *
     * Only the parquet footer is read, so this costs the same for a ten-megabyte table and a
     * ten-gigabyte one, and a file that is not the table it claims to be is refused here rather
     * than in the middle of somebody's run.
     */
    @POST
    @Path("{id}/complete")
    @Transactional
    fun complete(
        @PathParam("id") id: String,
    ): TraceWire {
        val trace = owned(id)
        // A table large enough to have been sent in parts becomes an object only here, since only
        // now is the browser finished sending them.
        val missing = trace.kind.tables.filterNot { store.completeUpload(traceKey(trace.publicId, it)) }
        if (missing.isNotEmpty()) {
            throw conflict("The bytes for ${missing.joinToString(", ")} never arrived")
        }

        for (table in trace.kind.tables) {
            val facts = ingest.inspect(trace.kind, table, traceKey(trace.publicId, table))
            val part =
                TracePart.find(trace.id, table) ?: TracePart().also {
                    it.trace = trace
                    it.tableName = table
                    it.persist()
                }
            part.sizeBytes = facts.sizeBytes
            part.rowCount = facts.rowCount
        }
        trace.updatedAt = Instant.now()
        return trace.toWire(TraceAccessWire.OWNED)
    }

    /**
     * The description is free text and always the owner's to change. The name is what documents
     * reference, so it may only be corrected while nothing references it.
     */
    @PATCH
    @Path("{id}")
    @Transactional
    fun edit(
        @PathParam("id") id: String,
        edit: TraceEdit,
    ): TraceWire {
        val trace = owned(id)
        edit.name?.let { requested ->
            val slug = "${trace.owner?.handle}/${traceName(requested)}"
            if (slug != trace.slug) {
                if (ExperimentResource.referencesTrace(trace.slug)) {
                    throw conflict("This trace is referenced by an experiment and can no longer be renamed")
                }
                if (Trace.findBySlug(slug) != null) {
                    throw conflict("You already have a trace called $slug")
                }
                trace.slug = slug
            }
        }
        edit.description?.let { trace.description = it.cleaned() }
        trace.updatedAt = Instant.now()
        return trace.toWire(TraceAccessWire.OWNED)
    }

    @DELETE
    @Path("{id}")
    @Transactional
    fun delete(
        @PathParam("id") id: String,
    ): Response {
        val trace = owned(id)
        if (ExperimentResource.referencesTrace(trace.slug)) {
            throw conflict("This trace is referenced by an experiment and cannot be deleted")
        }
        disposal.discard(trace)
        return Response.status(204).build()
    }

    /**
     * The trace as a zip of its tables. One archive rather than a file per table keeps a two-file
     * workload and a one-file carbon trace the same single download, and the entries inside are
     * laid out the way a reader expects to find them.
     */
    @GET
    @Path("{id}/content")
    @Produces("application/zip")
    fun content(
        @PathParam("id") id: String,
    ): Response {
        val trace = visible(id)
        val tables = TracePart.findByTrace(trace.id).map { it.tableName }
        if (tables.isEmpty()) {
            throw notFound("Trace content")
        }
        val fileName = trace.slug.substringAfterLast('/')
        val publicId = trace.publicId
        val body =
            StreamingOutput { out ->
                ZipOutputStream(out).use { zip ->
                    zip.setLevel(Deflater.NO_COMPRESSION)
                    for (table in tables) {
                        zip.putNextEntry(ZipEntry("$fileName/$table.parquet"))
                        store.open(traceKey(publicId, table)).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        return Response
            .ok(body)
            .header("Content-Disposition", "attachment; filename=\"$fileName.zip\"")
            .build()
    }

    @GET
    @Path("{id}/shares")
    fun shares(
        @PathParam("id") id: String,
    ): List<TraceShare> = TraceGrant.findByTrace(owned(id).id).map { TraceShare(it.grantee.handle, it.grantee.displayName) }

    @POST
    @Path("{id}/shares")
    @Transactional
    fun share(
        @PathParam("id") id: String,
        request: ShareRequest,
    ): Response {
        val trace = owned(id)
        val grantee = UserAccount.findByHandle(request.handle.trim()) ?: throw notFound("Account ${request.handle}")
        if (grantee.id == trace.owner?.id) {
            throw conflict("This trace is already yours")
        }
        if (TraceGrant.findGrant(trace.id, grantee.id) == null) {
            val grant = TraceGrant()
            grant.trace = trace
            grant.grantee = grantee
            grant.grantedAt = Instant.now()
            grant.persist()
        }
        return Response.status(201).entity(TraceShare(grantee.handle, grantee.displayName)).build()
    }

    @DELETE
    @Path("{id}/shares/{handle}")
    @Transactional
    fun revoke(
        @PathParam("id") id: String,
        @PathParam("handle") handle: String,
    ): Response {
        val trace = owned(id)
        val grantee = UserAccount.findByHandle(handle) ?: throw notFound("Account $handle")
        TraceGrant.findGrant(trace.id, grantee.id)?.delete()
        return Response.status(204).build()
    }

    private fun slotFor(
        trace: Trace,
        table: String,
        sizeBytes: Long,
    ): UploadSlot =
        when (val target = store.uploadTarget(traceKey(trace.publicId, table), sizeBytes)) {
            is UploadTarget.Direct ->
                UploadSlot(table, target.parts.map { UploadPart(it.url, it.offset, it.length) }, direct = true)
            // A store the browser cannot reach takes the whole file in one request to this server,
            // so there is one part and it covers everything.
            UploadTarget.ThroughServer ->
                UploadSlot(
                    table,
                    listOf(UploadPart("api/v1/traces/${trace.publicId}/tables/$table", 0, sizeBytes)),
                    direct = false,
                )
        }

    /** A trace the caller may read, whether it is the deployment's, theirs, or shared with them. */
    private fun visible(id: String): Trace {
        val trace = Trace.findByPublicId(publicId(id, "Trace")) ?: throw notFound("Trace")
        accessTo(trace)
        return trace
    }

    /**
     * A trace the caller may change. One they cannot see at all is a miss; one they can see but do
     * not own is a refusal, because pretending it is absent would contradict the listing they just
     * read it from.
     */
    private fun owned(id: String): Trace {
        val trace = visible(id)
        return when (accessTo(trace)) {
            TraceAccessWire.OWNED -> trace
            TraceAccessWire.BUILTIN -> throw forbidden("Built-in traces belong to the deployment")
            TraceAccessWire.SHARED -> throw forbidden("This trace belongs to somebody else")
        }
    }

    private fun accessTo(trace: Trace): TraceAccessWire {
        val user = identity.currentUser()
        return when {
            trace.origin == TraceOrigin.BUILTIN -> TraceAccessWire.BUILTIN
            trace.owner?.id == user.id -> TraceAccessWire.OWNED
            TraceGrant.findGrant(trace.id, user.id) != null -> TraceAccessWire.SHARED
            else -> throw notFound("Trace")
        }
    }

    /** Every table of the given traces, grouped by the trace it belongs to. */
    private fun tablesOf(traceIds: List<Long>): Map<Long, List<TracePart>> =
        if (traceIds.isEmpty()) emptyMap() else TracePart.list("trace.id in ?1", traceIds).groupBy { it.trace.id }

    private fun Map<Long, List<TracePart>>.holdsAll(trace: Trace): Boolean =
        this[trace.id].orEmpty().map { it.tableName }.containsAll(trace.kind.tables)

    private fun Trace.toWire(
        access: TraceAccessWire,
        stored: List<TracePart> = TracePart.findByTrace(id),
    ): TraceWire {
        val parts = stored.map { TraceTable(it.tableName, it.sizeBytes, it.rowCount) }
        return TraceWire(
            id = publicId.toString(),
            slug = slug,
            kind = kind.toWire(),
            access = access,
            description = description,
            sizeBytes = parts.sumOf { it.sizeBytes },
            tables = parts.sortedBy { it.name },
            createdAt = createdAt.toString(),
        )
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

/**
 * How large each of [kind]'s tables is said to be, refusing the registration unless every one of
 * them is accounted for.
 *
 * A file of no bytes is turned away here too. Nothing empty is a parquet table, and saying so now
 * costs the caller a round trip instead of an upload.
 */
private fun declaredSizes(
    kind: TraceKind,
    files: List<UploadedFile>,
): Map<String, Long> {
    val given = files.associate { it.table to it.sizeBytes }
    val unaccounted = kind.tables.filter { (given[it] ?: 0L) <= 0L }
    if (unaccounted.isNotEmpty()) {
        throw invalidDocument(
            "Every file has to be accounted for before any of it is sent",
            unaccounted.map { DocumentIssue(it, "needs a size in bytes") },
        )
    }
    return given
}

// The slug goes into documents, URLs and object keys, so it holds to what all three accept. The
// owner's handle and a separating slash are added around it, and are not the caller's to supply.
private val TRACE_NAME = Regex("[a-z0-9][a-z0-9._-]{0,99}")

private fun traceName(raw: String): String {
    val name = raw.trim().lowercase()
    if (!TRACE_NAME.matches(name)) {
        throw invalidDocument(
            "That is not a usable trace name",
            listOf(DocumentIssue("name", "use lower-case letters, digits, dots, dashes and underscores")),
        )
    }
    return name
}

private fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun TraceKind.toWire(): TraceKindWire =
    when (this) {
        TraceKind.WORKLOAD -> TraceKindWire.WORKLOAD
        TraceKind.CARBON -> TraceKindWire.CARBON
        TraceKind.FAILURE -> TraceKindWire.FAILURE
    }
