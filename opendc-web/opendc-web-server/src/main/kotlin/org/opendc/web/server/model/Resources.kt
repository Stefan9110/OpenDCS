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

package org.opendc.web.server.model

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanion
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.intellij.lang.annotations.Language
import org.opendc.trace.conv.TABLE_CARBON
import org.opendc.trace.conv.TABLE_FAILURES
import org.opendc.trace.conv.TABLE_FRAGMENTS
import org.opendc.trace.conv.TABLE_TASKS
import java.time.Instant
import java.util.UUID

/**
 * What a trace supplies to a run, and which opendc-trace tables it is made of. A workload carries
 * two, the others one each; a kind that needs a third is one entry in this list and no migration,
 * because a part is named by its table rather than by a position in a fixed set.
 *
 * The names come from [org.opendc.trace.conv], which is what the readers resolve against, so this
 * cannot drift into naming a table nothing can open.
 */
enum class TraceKind(val tables: List<String>) {
    WORKLOAD(listOf(TABLE_TASKS, TABLE_FRAGMENTS)),
    CARBON(listOf(TABLE_CARBON)),
    FAILURE(listOf(TABLE_FAILURES)),
}

/**
 * Where a trace came from, and with it whether anybody owns it. A built-in ships with the
 * deployment and is resolved from the repository, so it has no owner and no stored bytes; an
 * upload has both. This is the variant a nullable owner would otherwise have to stand in for.
 */
enum class TraceOrigin {
    BUILTIN,
    UPLOADED,
}

@Entity
@Table(name = "traces")
class Trace : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    var publicId: UUID = UUID.randomUUID()

    /**
     * What an experiment document references this trace by, unique platform-wide: bare for a
     * built-in, prefixed with the owner's handle for an upload. It may only change while nothing
     * references it, so a submitted experiment's provenance keeps resolving.
     */
    lateinit var slug: String

    @Enumerated(EnumType.STRING)
    lateinit var kind: TraceKind

    @Enumerated(EnumType.STRING)
    var origin: TraceOrigin = TraceOrigin.UPLOADED

    @ManyToOne(fetch = FetchType.LAZY)
    var owner: UserAccount? = null

    var description: String? = null

    lateinit var createdAt: Instant

    lateinit var updatedAt: Instant

    companion object : PanacheCompanion<Trace> {
        fun findBySlug(slug: String): Trace? = find("slug = ?1", slug).firstResult()

        fun findByPublicId(publicId: UUID): Trace? = find("publicId = ?1", publicId).firstResult()

        fun findBuiltIns(): List<Trace> = list("origin = ?1", TraceOrigin.BUILTIN)

        fun findOwnedBy(ownerId: Long): List<Trace> = list("owner.id = ?1", ownerId)
    }
}

/**
 * One table of a trace, named as opendc-trace names it. The row exists once the object behind it
 * does, so a trace is ready exactly when it has a part for every table its kind names.
 */
@Entity
@Table(name = "trace_parts")
class TracePart : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    lateinit var trace: Trace

    lateinit var tableName: String

    var sizeBytes: Long = 0

    /** Rows in the table, read from the parquet footer rather than by counting them. */
    var rowCount: Long? = null

    companion object : PanacheCompanion<TracePart> {
        fun findByTrace(traceId: Long): List<TracePart> = list("trace.id = ?1", traceId)

        fun find(
            traceId: Long,
            tableName: String,
        ): TracePart? = find("trace.id = ?1 and tableName = ?2", traceId, tableName).firstResult()
    }
}

/**
 * A trace shared with one person. The grant governs whether the trace appears in their library and
 * whether they may reference it in a new experiment; experiments already submitted pin the content
 * and are unaffected when it is revoked.
 */
@Entity
@Table(name = "trace_grants")
class TraceGrant : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    lateinit var trace: Trace

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    lateinit var grantee: UserAccount

    lateinit var grantedAt: Instant

    companion object : PanacheCompanion<TraceGrant> {
        // The trace is fetched with the grants because every caller reads it; left lazy, listing
        // what somebody has been shared costs a query per row.
        @Language("JPAQL")
        private const val SHARED_WITH = """
            SELECT g FROM TraceGrant g
            JOIN FETCH g.trace
            WHERE g.grantee.id = ?1
        """

        fun findSharedWith(granteeId: Long): List<TraceGrant> = list(SHARED_WITH, granteeId)

        fun findGrant(
            traceId: Long,
            granteeId: Long,
        ): TraceGrant? = find("trace.id = ?1 and grantee.id = ?2", traceId, granteeId).firstResult()

        fun findByTrace(traceId: Long): List<TraceGrant> = list("trace.id = ?1", traceId)
    }
}

@Entity
@Table(name = "experiment_resources")
class ExperimentResource : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    lateinit var experiment: Experiment

    @Enumerated(EnumType.STRING)
    lateinit var kind: TraceKind

    @JdbcTypeCode(SqlTypes.JSON)
    lateinit var reference: String

    var referenceName: String? = null

    companion object : PanacheCompanion<ExperimentResource> {
        fun referencesTrace(slug: String): Boolean = count("referenceName = ?1", slug) > 0
    }
}
