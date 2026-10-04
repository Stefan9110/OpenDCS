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

import io.quarkus.runtime.LaunchMode
import io.quarkus.runtime.StartupEvent
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.transaction.Transactional
import org.opendc.web.server.model.PlanTier
import org.opendc.web.server.model.UserAccount
import org.slf4j.LoggerFactory
import java.time.Instant

/** How a deployment tells its callers apart. */
enum class AuthMode {
    /** Auth0 access tokens. */
    AUTH0,

    /** No sign-in: every request acts as one implicit administrator account, metered against nothing. */
    ANONYMOUS,
}

@ConfigMapping(prefix = "opendc.auth")
interface AuthConfig {
    // The secure mode is the default on purpose: a deployment that forgets to configure this gets
    // authentication rather than an open administrator account.
    @WithDefault("auth0")
    fun mode(): AuthMode
}

/** Who the implicit account of anonymous mode is to the identity provider, which it never meets. */
const val IMPLICIT_SUBJECT = "anonymous"

const val IMPLICIT_HANDLE = "local"

/**
 * Puts the implicit account in place before anything asks for it, since anonymous mode resolves
 * every request to it and a self-hosted deployment has no identity provider to create one.
 *
 * Anonymous mode is allowed in a deployment, for a single person running OpenDC for themselves, but
 * it is said out loud at boot: anyone who can reach the port is the administrator.
 */
@ApplicationScoped
class ImplicitAccount(private val config: AuthConfig) {
    @Transactional
    internal fun seed(
        @Suppress("UNUSED_PARAMETER") @Observes event: StartupEvent,
    ) {
        if (config.mode() != AuthMode.ANONYMOUS) {
            return
        }
        if (LaunchMode.current() == LaunchMode.NORMAL) {
            LOG.warn("opendc.auth.mode=anonymous: every request acts as one implicit administrator with no budget")
        }
        if (UserAccount.findBySubject(IMPLICIT_SUBJECT) != null) {
            return
        }
        val account = UserAccount()
        account.subject = IMPLICIT_SUBJECT
        account.handle = IMPLICIT_HANDLE
        account.displayName = "Local user"
        account.planTier = PlanTier.FREE
        account.isAdmin = true
        account.createdAt = Instant.now()
        account.persist()
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(ImplicitAccount::class.java)
    }
}
