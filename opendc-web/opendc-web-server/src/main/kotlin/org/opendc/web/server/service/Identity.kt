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

import io.quarkus.runtime.LaunchMode
import io.quarkus.runtime.StartupEvent
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.RequestScoped
import jakarta.enterprise.event.Observes
import jakarta.transaction.Transactional
import org.opendc.web.server.model.PlanTier
import org.opendc.web.server.model.UserAccount
import org.opendc.web.server.rest.notAuthenticated
import java.time.Instant

enum class AuthMode {
    /** No sign-in: every request acts as one implicit admin account. Dev and test only. */
    DEVELOPER,

    /** Auth0 OIDC. The only mode a deployment may run in. */
    AUTH0,
}

@ConfigMapping(prefix = "opendc")
fun interface OpenDcConfig {
    // Defaults to the secure mode on purpose: a deployment that forgets to configure this gets
    // authentication rather than an open admin account.
    @WithDefault("auth0")
    fun authMode(): AuthMode
}

private const val DEVELOPER_SUBJECT = "developer"

/**
 * Resolves the account a request acts as. In developer mode that is one implicit admin account
 * seeded at startup, which is what makes a fresh checkout usable with no identity provider. Auth0
 * and personal access tokens arrive behind this same seam.
 *
 * Scoped to the request and answered once within it. A single request asks who the caller is
 * several times over, since every ownership check does, and the account is found by its subject
 * rather than by its key, so this is a query that the persistence context cannot spare.
 */
@RequestScoped
class Identity(private val config: OpenDcConfig) {
    private val caller by lazy(LazyThreadSafetyMode.NONE) { resolve() }

    fun currentUser(): UserAccount = caller

    private fun resolve(): UserAccount =
        when (config.authMode()) {
            AuthMode.DEVELOPER ->
                checkNotNull(UserAccount.findBySubject(DEVELOPER_SUBJECT)) {
                    "the developer account is seeded at startup"
                }
            // Until OIDC is wired every request in this mode is unauthenticated, which is what the
            // frontend's sign-in gate expects to see.
            AuthMode.AUTH0 -> throw notAuthenticated()
        }
}

/**
 * Puts the implicit account in place before anything asks for it, since developer mode resolves
 * every request to it and a fresh checkout has no identity provider to create one.
 */
@ApplicationScoped
class DeveloperAccount(private val config: OpenDcConfig) {
    @Transactional
    internal fun seed(
        @Suppress("UNUSED_PARAMETER") @Observes event: StartupEvent,
    ) {
        if (config.authMode() != AuthMode.DEVELOPER) {
            return
        }
        require(LaunchMode.current() != LaunchMode.NORMAL) {
            "opendc.auth-mode=developer grants every request an admin account and is refused outside dev and test"
        }
        if (UserAccount.findBySubject(DEVELOPER_SUBJECT) != null) {
            return
        }
        val account = UserAccount()
        account.subject = DEVELOPER_SUBJECT
        account.handle = "opendcdev"
        account.displayName = "Developer"
        account.planTier = PlanTier.FREE
        account.isAdmin = true
        account.createdAt = Instant.now()
        account.persist()
    }
}
