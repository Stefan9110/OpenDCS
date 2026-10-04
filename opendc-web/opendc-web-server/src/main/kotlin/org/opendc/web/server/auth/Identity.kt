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

import jakarta.enterprise.context.RequestScoped
import org.opendc.web.server.model.UserAccount
import org.opendc.web.server.rest.notAuthenticated

/**
 * Resolves the account a request acts as. In anonymous mode that is the implicit account seeded at
 * startup, which is what makes a fresh checkout or a self-hosted deployment usable with no identity
 * provider. Auth0 and personal access tokens arrive behind this same seam.
 *
 * Scoped to the request and answered once within it. A single request asks who the caller is
 * several times over, since every ownership check does, and the account is found by its subject
 * rather than by its key, so this is a query that the persistence context cannot spare.
 */
@RequestScoped
class Identity(private val config: AuthConfig) {
    private val caller by lazy(LazyThreadSafetyMode.NONE) { resolve() }

    fun currentUser(): UserAccount = caller

    private fun resolve(): UserAccount =
        when (config.mode()) {
            AuthMode.ANONYMOUS ->
                checkNotNull(UserAccount.findBySubject(IMPLICIT_SUBJECT)) {
                    "the implicit account is seeded at startup"
                }
            // Until OIDC is wired every request in this mode is unauthenticated, which is what the
            // frontend's sign-in gate expects to see.
            AuthMode.AUTH0 -> throw notAuthenticated()
        }
}
