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

import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.opendc.web.server.ApiTest
import org.opendc.web.server.model.PlanTier
import org.opendc.web.server.model.Trace
import org.opendc.web.server.model.TraceGrant
import org.opendc.web.server.model.TraceKind
import org.opendc.web.server.model.TraceOrigin
import org.opendc.web.server.model.TracePart
import org.opendc.web.server.model.UserAccount
import java.time.Instant
import java.util.UUID

@QuarkusTest
class TracesResourceTest {
    // Built-ins carry no owner prefix because they belong to the deployment, and no parts because
    // the SDK's registry resolves them from the repository rather than from object storage.
    @Test
    fun `the deployment's own traces are listed to everyone with a bare slug`() {
        val builtIns = rows().filter { it.access() == "builtin" }
        assertEquals(
            setOf("bitbrains-small", "surf-week", "surf-month"),
            builtIns.map { it.slug() }.toSet(),
        )
        for (row in builtIns) {
            assertEquals("workload", row.getValue("kind").jsonPrimitive.content)
            assertTrue('/' !in row.slug(), "a built-in slug carries no owner prefix: ${row.slug()}")
            Instant.parse(row.getValue("createdAt").jsonPrimitive.content)
        }
    }

    @Test
    fun `an upload is listed to its owner as theirs, under their handle`() {
        val slug = seedUpload("developer/nightly-${UUID.randomUUID()}", TraceKind.CARBON)

        val mine = rows().single { it.slug() == slug }
        assertEquals("owned", mine.access())
        assertEquals("carbon", mine.getValue("kind").jsonPrimitive.content)
        UUID.fromString(mine.getValue("id").jsonPrimitive.content)
    }

    // Size is summed from the trace's parts rather than stored on the trace, so a two-table
    // workload reports the total of both files.
    @Test
    fun `size is the total of the trace's tables`() {
        val slug =
            seedUpload("developer/sized-${UUID.randomUUID()}", TraceKind.WORKLOAD, parts = mapOf("tasks" to 100L, "fragments" to 400L))
        assertEquals(500L, rows().single { it.slug() == slug }.getValue("sizeBytes").jsonPrimitive.content.toLong())
    }

    @Test
    fun `somebody else's trace appears only once it is shared, and then as shared`() {
        val (slug, traceId) = seedForeignUpload()
        assertTrue(rows().none { it.slug() == slug }, "an ungranted trace must not be listed")

        grantToDeveloper(traceId)
        assertEquals("shared", rows().single { it.slug() == slug }.access())
    }

    @Test
    fun `kind filter narrows the listing`() {
        val slug = seedUpload("developer/carbon-${UUID.randomUUID()}", TraceKind.CARBON)
        assertTrue(rows("?kind=carbon").all { it.getValue("kind").jsonPrimitive.content == "carbon" })
        assertTrue(rows("?kind=carbon").any { it.slug() == slug })
        assertTrue(rows("?kind=workload").none { it.slug() == slug })
    }

    // Answering an empty list would tell a caller who misspelled "carbon" that they simply have no
    // carbon traces, which is a different and wrong answer.
    @Test
    fun `a kind nobody serves is a bad request, not an empty library`() {
        get("/api/v1/traces?kind=carbn").then().statusCode(400).body("issues[0].path", equalTo("kind"))
    }

    // The origin variant is only real if the database refuses the states it excludes. Without this
    // the column is a comment and "owner is null" quietly means built-in again.
    @Test
    fun `the schema refuses a trace whose origin and owner disagree`() {
        assertThrows<Exception> {
            QuarkusTransaction.requiringNew().run {
                val orphan = Trace()
                orphan.slug = "orphan-${UUID.randomUUID()}"
                orphan.kind = TraceKind.CARBON
                orphan.origin = TraceOrigin.UPLOADED
                orphan.owner = null
                orphan.createdAt = Instant.now()
                orphan.updatedAt = Instant.now()
                orphan.persist()
            }
        }

        assertThrows<Exception> {
            QuarkusTransaction.requiringNew().run {
                val owned = Trace()
                owned.slug = "owned-builtin-${UUID.randomUUID()}"
                owned.kind = TraceKind.CARBON
                owned.origin = TraceOrigin.BUILTIN
                owned.owner = checkNotNull(UserAccount.findBySubject("developer"))
                owned.createdAt = Instant.now()
                owned.updatedAt = Instant.now()
                owned.persist()
            }
        }
    }

    private fun rows(query: String = ""): List<JsonObject> = getArray("/api/v1/traces$query").map { it.jsonObject }

    private fun JsonObject.slug(): String = getValue("slug").jsonPrimitive.content

    private fun JsonObject.access(): String = getValue("access").jsonPrimitive.content

    private fun getArray(path: String): JsonArray =
        ApiTest.json.parseToJsonElement(get(path).then().statusCode(200).extract().asString()).jsonArray

    private fun seedUpload(
        slug: String,
        kind: TraceKind,
        parts: Map<String, Long> = emptyMap(),
    ): String =
        QuarkusTransaction.requiringNew().call {
            val owner = checkNotNull(UserAccount.findBySubject("developer"))
            newTrace(slug, kind, owner, parts)
            slug
        }

    private fun seedForeignUpload(): Pair<String, Long> =
        QuarkusTransaction.requiringNew().call {
            val stranger = UserAccount()
            stranger.subject = "stranger-${UUID.randomUUID()}"
            stranger.handle = "stranger-${UUID.randomUUID()}"
            stranger.displayName = "Stranger"
            stranger.planTier = PlanTier.FREE
            stranger.createdAt = Instant.now()
            stranger.persist()
            val slug = "${stranger.handle}/private-${UUID.randomUUID()}"
            slug to newTrace(slug, TraceKind.CARBON, stranger, emptyMap())
        }

    private fun grantToDeveloper(traceId: Long) {
        QuarkusTransaction.requiringNew().run {
            val grant = TraceGrant()
            grant.trace = checkNotNull(Trace.findById(traceId))
            grant.grantee = checkNotNull(UserAccount.findBySubject("developer"))
            grant.grantedAt = Instant.now()
            grant.persist()
        }
    }

    private fun newTrace(
        slug: String,
        kind: TraceKind,
        owner: UserAccount,
        parts: Map<String, Long>,
    ): Long {
        val now = Instant.now()
        val trace = Trace()
        trace.slug = slug
        trace.kind = kind
        trace.origin = TraceOrigin.UPLOADED
        trace.owner = owner
        trace.createdAt = now
        trace.updatedAt = now
        trace.persist()
        for ((table, size) in parts) {
            val part = TracePart()
            part.trace = trace
            part.tableName = table
            part.contentHash = "hash-$table-${trace.publicId}"
            part.sizeBytes = size
            part.persist()
        }
        return trace.id
    }
}
