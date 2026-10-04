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
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.opendc.web.server.model.PlanTier
import org.opendc.web.server.model.UserAccount
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Optional

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

    fun auth0(): Auth0Tenant

    /** The Auth0 application the frontend signs in through. Read only in auth0 mode. */
    interface Auth0Tenant {
        fun domain(): Optional<String>

        fun clientId(): Optional<String>

        fun audience(): Optional<String>
    }
}

/** How the frontend is to sign in, which it learns from `/config` rather than from its own build. */
@Serializable
sealed interface AuthSettings {
    @Serializable
    @SerialName("auth0")
    data class Auth0(
        val domain: String,
        val clientId: String,
        val audience: String,
    ) : AuthSettings

    @Serializable
    @SerialName("anonymous")
    data object Anonymous : AuthSettings
}

/** The sign-in [config] describes. A missing Auth0 setting is an error naming its key. */
fun authSettings(config: AuthConfig): AuthSettings =
    when (config.mode()) {
        AuthMode.ANONYMOUS -> AuthSettings.Anonymous
        AuthMode.AUTH0 -> {
            val tenant = config.auth0()
            AuthSettings.Auth0(
                domain = tenant.domain().orElseThrow { missingAuth0Setting("domain") },
                clientId = tenant.clientId().orElseThrow { missingAuth0Setting("client-id") },
                audience = tenant.audience().orElseThrow { missingAuth0Setting("audience") },
            )
        }
    }

private fun missingAuth0Setting(key: String) = IllegalStateException("opendc.auth.mode=auth0 needs opendc.auth.auth0.$key")

/** Who the implicit account of anonymous mode is to the identity provider, which it never meets. */
const val IMPLICIT_SUBJECT = "anonymous"

const val IMPLICIT_HANDLE = "local"

/**
 * Settles how this deployment signs callers in, once and at boot, so a misconfigured one fails to
 * start rather than at its first sign-in.
 *
 * Anonymous mode resolves every request to an implicit account, which is put in place here before
 * anything asks for it. That mode is allowed in a deployment, for a single person running OpenDC for
 * themselves, but it is said out loud at boot: anyone who can reach the port is the administrator.
 */
@ApplicationScoped
class AuthSetup(private val config: AuthConfig) {
    @Produces
    @Singleton
    fun settings(): AuthSettings = authSettings(config)

    @Transactional
    internal fun onStart(
        @Suppress("UNUSED_PARAMETER") @Observes event: StartupEvent,
        settings: AuthSettings,
    ) {
        if (settings is AuthSettings.Anonymous) {
            seedImplicitAccount()
        }
    }

    private fun seedImplicitAccount() {
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
        val LOG = LoggerFactory.getLogger(AuthSetup::class.java)
    }
}
