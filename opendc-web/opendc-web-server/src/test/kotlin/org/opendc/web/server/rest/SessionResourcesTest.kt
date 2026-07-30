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
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.opendc.web.server.ApiTest

@QuarkusTest
class SessionResourcesTest {
    @Test
    fun `config reports the developer auth mode`() {
        ApiTest.requestJson()
            .get("/api/v1/config")
            .then()
            .statusCode(200)
            .body("authMode", equalTo("developer"))
    }

    // An account with no email must omit the field rather than send an explicit null: the client
    // reads a present key as a value, and "null" would render as an address.
    @Test
    fun `me exposes the implicit developer account`() {
        ApiTest.requestJson()
            .get("/api/v1/me")
            .then()
            .statusCode(200)
            .body("subject", equalTo("developer"))
            .body("displayName", equalTo("Developer"))
            .body("plan", equalTo("free"))
            .body("isAdmin", equalTo(true))
            .body("$", not(hasKey("email")))
    }

    // Developer mode meters nothing, so there is no window to charge against. Reporting an uncapped
    // one instead would draw a bar that can never move and a reset time that never arrives.
    @Test
    fun `me reports no budget window when nothing is metered`() {
        ApiTest.requestJson()
            .get("/api/v1/me")
            .then()
            .statusCode(200)
            .body("budgets", empty<Any>())
    }

    @Test
    fun `projectCount follows the caller's memberships`() {
        val before =
            ApiTest.requestJson()
                .get("/api/v1/me")
                .then()
                .statusCode(200)
                .extract()
                .path<Int>("projectCount")

        ApiTest.requestJson()
            .body("""{"name":"Membership probe ${System.nanoTime()}"}""")
            .post("/api/v1/projects")
            .then()
            .statusCode(201)

        ApiTest.requestJson()
            .get("/api/v1/me")
            .then()
            .statusCode(200)
            .body("projectCount", equalTo(before + 1))
    }

    @Test
    fun `billing has no invoices and no provider fields yet`() {
        ApiTest.requestJson()
            .get("/api/v1/me/billing")
            .then()
            .statusCode(200)
            .body("invoices", empty<Any>())
            .body("$", not(hasKey("renewsAt")))
            .body("$", not(hasKey("paymentMethod")))
    }
}
