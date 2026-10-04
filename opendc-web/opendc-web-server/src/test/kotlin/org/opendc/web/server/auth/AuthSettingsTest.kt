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

package org.opendc.web.server.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Optional

/**
 * A deployment that cannot sign anybody in has to say which setting it is missing before it starts
 * taking requests, not answer every visitor with a sign-in page that leads nowhere.
 */
class AuthSettingsTest {
    @Test
    fun `an Auth0 deployment hands the frontend the tenant it signs in through`() {
        val settings = authSettings(config(AuthMode.AUTH0, domain = "opendc.eu.auth0.com", clientId = "spa", audience = "https://api"))

        assertEquals(AuthSettings.Auth0(domain = "opendc.eu.auth0.com", clientId = "spa", audience = "https://api"), settings)
    }

    @Test
    fun `an Auth0 deployment missing a setting names the key it lacks`() {
        val failure =
            assertThrows<IllegalStateException> {
                authSettings(config(AuthMode.AUTH0, domain = "opendc.eu.auth0.com", clientId = null, audience = "https://api"))
            }

        assertTrue("opendc.auth.auth0.client-id" in failure.message.orEmpty()) { "unhelpful message: ${failure.message}" }
    }

    @Test
    fun `an anonymous deployment needs no tenant at all`() {
        assertEquals(AuthSettings.Anonymous, authSettings(config(AuthMode.ANONYMOUS, domain = null, clientId = null, audience = null)))
    }

    private fun config(
        mode: AuthMode,
        domain: String?,
        clientId: String?,
        audience: String?,
    ): AuthConfig =
        object : AuthConfig {
            override fun mode(): AuthMode = mode

            override fun auth0(): AuthConfig.Auth0Tenant =
                object : AuthConfig.Auth0Tenant {
                    override fun domain(): Optional<String> = Optional.ofNullable(domain)

                    override fun clientId(): Optional<String> = Optional.ofNullable(clientId)

                    override fun audience(): Optional<String> = Optional.ofNullable(audience)
                }
        }
}
