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

import io.quarkus.test.junit.QuarkusTest
import io.restassured.response.Response
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.opendc.web.server.ApiTest
import java.time.Instant
import java.util.UUID

@QuarkusTest
class ExperimentsResourceTest {
    private lateinit var projectId: String

    @BeforeEach
    fun seedProject() {
        projectId =
            ApiTest.requestJson()
                .body("""{"name":"Experiments ${UUID.randomUUID()}"}""")
                .post("/api/v1/projects")
                .then()
                .statusCode(201)
                .extract()
                .path("id")
    }

    @Test
    fun aDraftIsCreatedWithAnEstimateAndNoSubmissionTime() {
        val created = createDraft("First")
        created
            .then()
            .statusCode(201)
            .body("name", equalTo("First"))
            .body("state", equalTo("draft"))
            .body("projectId", equalTo(projectId))
            .body("submittedAt", equalTo(null))
        assertTrue(created.jsonPath().getString("specHash").isNotBlank())
        Instant.parse(created.jsonPath().getString("createdAt"))
    }

    @Test
    fun draftsAreEditableAndSubmittedExperimentsAreNot() {
        val id = draftId("Editable")

        ApiTest.requestJson()
            .body("""{"name":"Renamed","spec":$SPEC}""")
            .put("/api/v1/experiments/$id")
            .then()
            .statusCode(200)
            .body("name", equalTo("Renamed"))

        ApiTest.requestJson().post("/api/v1/experiments/$id/submit").then().statusCode(200)

        ApiTest.requestJson()
            .body("""{"name":"Too late","spec":$SPEC}""")
            .put("/api/v1/experiments/$id")
            .then()
            .statusCode(409)
    }

    @Test
    fun submitQueuesTheExperimentAndRefusesASecondTime() {
        val id = draftId("Submit once")

        val submitted = ApiTest.requestJson().post("/api/v1/experiments/$id/submit")
        submitted.then().statusCode(200).body("state", equalTo("queued"))
        Instant.parse(submitted.jsonPath().getString("submittedAt"))

        ApiTest.requestJson().post("/api/v1/experiments/$id/submit").then().statusCode(409)
    }

    // A queued experiment has run units, so status reports one entry per scenario. Nothing has run
    // yet, so each one is queued with no exit info, which is what the progress view folds over.
    @Test
    fun statusReportsAScenarioPerExpandedRunUnit() {
        val id = draftId("Status")
        ApiTest.requestJson().post("/api/v1/experiments/$id/submit").then().statusCode(200)

        val status = ApiTest.requestJson().get("/api/v1/experiments/$id/status")
        status
            .then()
            .statusCode(200)
            .body("state", equalTo("queued"))
            .body("scenarioCount", greaterThan(0))
            .body("scenarios[0].state", equalTo("queued"))
            .body("scenarios[0].scenarioIndex", equalTo(0))
        assertEquals(status.jsonPath().getInt("scenarioCount"), status.jsonPath().getList<Any>("scenarios").size)

        ApiTest.requestJson().get("/api/v1/experiments/$id/scenarios/0").then().statusCode(200)
        ApiTest.requestJson().get("/api/v1/experiments/$id/scenarios/99").then().statusCode(404)
    }

    // A draft has expanded into nothing yet, so asking for its scenarios is a miss rather than an
    // empty success: there is no scenario 0 to report on.
    @Test
    fun aDraftHasNoScenariosToReportOn() {
        val id = draftId("Unsubmitted")
        ApiTest.requestJson().get("/api/v1/experiments/$id/status").then().statusCode(200).body("state", equalTo("draft"))
        ApiTest.requestJson().get("/api/v1/experiments/$id/scenarios/0").then().statusCode(404)
    }

    // The series array carries no default, so it must be emitted even when empty: a chart that
    // receives no array at all cannot tell "nothing reported yet" from a malformed response.
    @Test
    fun resultsAreAnHonestEmptySetUntilSomethingRuns() {
        val id = draftId("Results")
        ApiTest.requestJson().post("/api/v1/experiments/$id/submit").then().statusCode(200)

        ApiTest.requestJson()
            .get("/api/v1/experiments/$id/results")
            .then()
            .statusCode(200)
            .body("experimentId", equalTo(id))
            .body("complete", equalTo(false))
            .body("scenarios.size()", equalTo(0))
            .body("exportIntervalMs", greaterThan(0))
    }

    @Test
    fun cancelAppliesOnlyToAnExperimentThatIsActuallyRunning() {
        val id = draftId("Cancel")
        ApiTest.requestJson().post("/api/v1/experiments/$id/cancel").then().statusCode(409)

        ApiTest.requestJson().post("/api/v1/experiments/$id/submit").then().statusCode(200)
        ApiTest.requestJson().post("/api/v1/experiments/$id/cancel").then().statusCode(200).body("state", equalTo("cancelled"))
        ApiTest.requestJson().post("/api/v1/experiments/$id/cancel").then().statusCode(409)
    }

    @Test
    fun cloningCopiesTheSpecIntoAFreshDraft() {
        val id = draftId("Original")
        ApiTest.requestJson().post("/api/v1/experiments/$id/submit").then().statusCode(200)
        val source = ApiTest.requestJson().get("/api/v1/experiments/$id")

        val clone = ApiTest.requestJson().post("/api/v1/experiments/$id/clone?name=Copy")
        clone.then().statusCode(201).body("name", equalTo("Copy")).body("state", equalTo("draft"))
        assertNotEquals(id, clone.jsonPath().getString("id"))
        assertEquals(source.jsonPath().getString("specHash"), clone.jsonPath().getString("specHash"))
    }

    // The browser client sends no body at all here, so an absent name must be the working default
    // rather than a deserialization failure.
    @Test
    fun cloningWithoutANameDerivesOneFromTheSource() {
        val id = draftId("Derived")
        ApiTest.requestJson().post("/api/v1/experiments/$id/clone").then().statusCode(201).body("name", equalTo("Derived (copy)"))
    }

    // Immutability governs editing a submitted experiment, not keeping it: an owner may always
    // delete their own work, and the run units go with it through the schema's cascade.
    @Test
    fun aSubmittedExperimentCanStillBeDeleted() {
        val id = draftId("Deletable")
        ApiTest.requestJson().post("/api/v1/experiments/$id/submit").then().statusCode(200)

        ApiTest.requestJson().delete("/api/v1/experiments/$id").then().statusCode(204)
        ApiTest.requestJson().get("/api/v1/experiments/$id").then().statusCode(404)
        ApiTest.requestJson().get("/api/v1/experiments/$id/status").then().statusCode(404)
    }

    @Test
    fun previewReportsTheScenarioCountWithoutStoringAnything() {
        val before = summaryCount()
        ApiTest.requestJson()
            .body("""{"spec":$SPEC}""")
            .post("/api/v1/experiments/preview")
            .then()
            .statusCode(200)
            .body("scenarioCount", greaterThan(0))
        assertEquals(before, summaryCount())
    }

    // Preview answers "what would happen", so an unfinished draft is a successful answer carrying
    // issues, not a failed request: the editor calls this while someone is still typing, and a 400
    // would render a half-built experiment as a broken one. Paths point at the axes that are empty
    // so the message lands on the field rather than at the top of the page.
    @Test
    fun previewReportsValidationIssuesAsDataRatherThanAsAnError() {
        ApiTest.requestJson()
            .body("""{"spec":{"topologies":[],"workloads":[]}}""")
            .post("/api/v1/experiments/preview")
            .then()
            .statusCode(200)
            .body("scenarioCount", equalTo(0))
            .body("issues.path", hasItems("topologies", "workloads"))
    }

    // Malformed content is different from unfinished content: nothing can be previewed at all, so
    // this one is a request error.
    @Test
    fun previewRejectsAnUnreadableDocument() {
        ApiTest.requestJson()
            .body("""{"spec":{"topologies":[],"workloads":[],"wattage":5}}""")
            .post("/api/v1/experiments/preview")
            .then()
            .statusCode(400)
    }

    @Test
    fun anUnknownKeyInTheSpecIsRejectedRatherThanIgnored() {
        ApiTest.requestJson()
            .body("""{"projectId":"$projectId","name":"Strict","spec":{"topologies":[],"workloads":[],"wattage":5}}""")
            .post("/api/v1/experiments")
            .then()
            .statusCode(400)
    }

    @Test
    fun blankAndOverlongNamesAreRejected() {
        ApiTest.requestJson()
            .body("""{"projectId":"$projectId","name":"  ","spec":$SPEC}""")
            .post("/api/v1/experiments")
            .then()
            .statusCode(400)
            .body("issues[0].path", equalTo("name"))

        ApiTest.requestJson()
            .body("""{"projectId":"$projectId","name":"${"x".repeat(256)}","spec":$SPEC}""")
            .post("/api/v1/experiments")
            .then()
            .statusCode(400)
            .body("issues[0].path", equalTo("name"))
    }

    @Test
    fun theListingIsScopedToTheProjectAndPageable() {
        val first = draftId("Page one")
        val second = draftId("Page two")

        val all = ApiTest.requestJson().get("/api/v1/experiments?project=$projectId").jsonPath().getList<String>("id")
        assertEquals(listOf(first, second), all)

        val page = ApiTest.requestJson().get("/api/v1/experiments?project=$projectId&limit=1&offset=1")
        page.then().statusCode(200)
        assertEquals(listOf(second), page.jsonPath().getList<String>("id"))
    }

    @Test
    fun everyEndpointHidesAnExperimentTheCallerCannotSee() {
        for (id in listOf(UUID.randomUUID().toString(), "not-an-id")) {
            ApiTest.requestJson().get("/api/v1/experiments/$id").then().statusCode(404)
            ApiTest.requestJson().get("/api/v1/experiments/$id/status").then().statusCode(404)
            ApiTest.requestJson().get("/api/v1/experiments/$id/results").then().statusCode(404)
            ApiTest.requestJson().post("/api/v1/experiments/$id/submit").then().statusCode(404)
            ApiTest.requestJson().post("/api/v1/experiments/$id/cancel").then().statusCode(404)
            ApiTest.requestJson().delete("/api/v1/experiments/$id").then().statusCode(404)
        }
    }

    private fun createDraft(name: String): Response =
        ApiTest.requestJson()
            .body("""{"projectId":"$projectId","name":"$name","spec":$SPEC}""")
            .post("/api/v1/experiments")

    private fun draftId(name: String): String = createDraft(name).then().statusCode(201).extract().path("id")

    private fun summaryCount(): Int = ApiTest.requestJson().get("/api/v1/experiments?project=$projectId").jsonPath().getList<Any>("id").size

    private companion object {
        val SPEC: String = ApiTest.fixture("minimal-experiment.json")
    }
}
