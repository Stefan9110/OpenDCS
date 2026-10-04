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

package org.opendc.web.server.service

import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.opendc.sdk.model.experiment.ExperimentSpec
import org.opendc.sdk.model.serialization.SdkJson
import org.opendc.sdk.model.topology.TopologySpec
import org.opendc.web.server.rest.DocumentIssue
import org.opendc.web.server.rest.invalidDocument
import java.security.MessageDigest

/**
 * The server's single JSON boundary around sdk-model documents, derived from [SdkJson] so the SDK
 * serializers stay the only experiment codec. Decoding is strict: an unknown key is a 400, not a
 * silent drop.
 */
@Singleton
class SpecCodec {
    /**
     * Canonical storage form: compact, with defaults materialized so the stored document is
     * self-describing and its hash does not depend on which fields the writer happened to omit.
     */
    val json: Json = Json(from = SdkJson.json) { prettyPrint = false }

    private val strict: Json = Json(from = SdkJson.strictJson) { prettyPrint = false }

    /**
     * The wire form. Defaults are deliberately not encoded: a field the server has no value for is
     * then absent rather than an explicit null. Clients read an absent field as "nothing here",
     * whereas a null is a value they must remember to guard on every access, and forgetting once is
     * a crash. Stored documents are unaffected: they travel as [JsonElement] trees, which serialize
     * as-is.
     */
    @Produces
    @Singleton
    fun wireJson(): Json = Json(from = json) { encodeDefaults = false }

    /** An experiment whose topologies are held in their data-center form only, as [decodeTopology] holds one. */
    fun decodeExperiment(document: JsonElement): ExperimentSpec {
        ((document as? JsonObject)?.get("topologies") as? JsonArray)?.forEachIndexed { index, topology ->
            requireDataCenterForm(topology, "topologies[$index].")
        }
        val spec = decode("experiment") { strict.decodeFromJsonElement<ExperimentSpec>(document) }
        return spec.copy(topologies = spec.topologies.map { dataCenterForm(it) }.toSet())
    }

    /**
     * A topology as data centers alone.
     *
     * A document written before data centers existed is converted by the SDK, which is lossy past the
     * first cluster's power source, and then forgets the clusters it was converted from. Nothing
     * reads them any more, and keeping them would leave two answers to where a cluster's power comes
     * from.
     */
    fun decodeTopology(document: JsonElement): TopologySpec {
        requireDataCenterForm(document, "")
        return dataCenterForm(decode("topology") { strict.decodeFromJsonElement<TopologySpec>(document) })
    }

    fun canonical(spec: ExperimentSpec): String = json.encodeToString(ExperimentSpec.serializer(), spec)

    fun canonical(spec: TopologySpec): String = json.encodeToString<TopologySpec>(spec)

    fun toElement(spec: ExperimentSpec): JsonElement = json.encodeToJsonElement(spec)

    fun parseStored(document: String): JsonElement = json.parseToJsonElement(document)

    fun hash(canonical: String): String =
        MessageDigest.getInstance("SHA-256").digest(canonical.encodeToByteArray()).joinToString("") { byte ->
            "%02x".format(byte)
        }

    /**
     * Refuses the two shapes the SDK's conversion cannot make sense of: data centers next to the
     * clusters they would be built from, which leaves two topologies in one document, and an empty
     * cluster list, which the conversion indexes into. A null `clusters` is the same as none.
     */
    private fun requireDataCenterForm(
        document: JsonElement,
        path: String,
    ) {
        val topology = document as? JsonObject ?: return
        val clusters = topology["clusters"]?.takeUnless { it is JsonNull } ?: return
        val dataCenters = topology["datacenters"]?.takeUnless { it is JsonNull }
        val message =
            if (dataCenters != null) {
                "must be absent when datacenters is given"
            } else if (clusters is JsonArray && clusters.isEmpty()) {
                "must not be empty"
            } else {
                return
            }
        throw invalidDocument("The topology document is invalid", listOf(DocumentIssue("${path}clusters", message)))
    }

    private fun dataCenterForm(spec: TopologySpec): TopologySpec =
        TopologySpec(
            datacenters =
                spec.datacenters?.map { dataCenter ->
                    dataCenter.copy(clusters = dataCenter.clusters.map { it.copy(powerSource = null, battery = null) })
                },
        )

    // The unit parsers inside sdk-model throw bare RuntimeExceptions for unparseable quantities
    // (e.g. "3 lightyears"), not SerializationExceptions, so the net here is deliberately wide;
    // anything thrown while decoding a user document is a validation failure of that document.
    private fun <T> decode(
        kind: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: Exception) {
            throw invalidDocument("The $kind document does not parse", listOf(DocumentIssue("", e.message ?: "unparseable document")))
        }
}
