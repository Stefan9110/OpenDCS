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

import jakarta.transaction.Transactional
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.opendc.web.server.auth.Identity
import org.opendc.web.server.auth.changeProfile
import org.opendc.web.server.auth.deactivate
import org.opendc.web.server.model.HandleKind
import org.opendc.web.server.model.PlanTier
import org.opendc.web.server.model.ProjectMember
import org.opendc.web.server.model.UserAccount
import org.opendc.web.server.service.TraceDisposal
import java.time.Instant

/**
 * The caller's own account: who they are, the handle they go by, and leaving.
 *
 * These are the only endpoints an account still choosing its handle may use, since choosing one is
 * what they are for.
 */
@Path("me")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class MeResource(
    private val identity: Identity,
    private val disposal: TraceDisposal,
) {
    @GET
    fun me(): UserProfile = identity.account().toProfile()

    @PUT
    @Path("profile")
    @Transactional
    fun updateProfile(change: ProfileChange): UserProfile {
        val account = identity.account()
        changeProfile(account, change.handle, change.displayName, disposal::discard)
        return account.toProfile()
    }

    /** Signs the caller out for good; see [deactivate] for what that leaves behind. */
    @DELETE
    @Transactional
    fun deactivateAccount(): Response {
        deactivate(identity.account(), Instant.now())
        return Response.noContent().build()
    }

    @GET
    @Path("billing")
    fun billing(): Billing {
        identity.currentUser()
        return Billing(invoices = emptyList())
    }
}

private fun UserAccount.toProfile(): UserProfile =
    UserProfile(
        displayName = displayName,
        handle =
            when (handleKind) {
                HandleKind.PROVISIONAL -> HandleWire.Provisional
                HandleKind.CHOSEN -> HandleWire.Chosen(handle)
            },
        plan = planTier.toWire(),
        isAdmin = isAdmin,
        projectCount = ProjectMember.count("user.id = ?1", id).toInt(),
        // Anonymous mode meters nothing, so the account is charged against no window at all.
        // Reporting an uncapped one instead would draw a bar that can never move and a reset
        // time that never arrives, which says less than saying nothing.
        budgets = emptyList(),
    )

@Serializable
data class ProfileChange(
    val handle: String,
    val displayName: String,
)

/** The name other people see, or that there is none yet: a first sign-in has to choose one. */
@Serializable
sealed interface HandleWire {
    @Serializable
    @SerialName("provisional")
    data object Provisional : HandleWire

    @Serializable
    @SerialName("chosen")
    data class Chosen(val name: String) : HandleWire
}

@Serializable
enum class WirePlan {
    @SerialName("free")
    FREE,

    @SerialName("education")
    EDUCATION,

    @SerialName("enterprise")
    ENTERPRISE,
}

fun PlanTier.toWire(): WirePlan =
    when (this) {
        PlanTier.FREE -> WirePlan.FREE
        PlanTier.EDUCATION -> WirePlan.EDUCATION
        PlanTier.ENTERPRISE -> WirePlan.ENTERPRISE
    }

/**
 * The windows an account can be metered over. Both exist because `budget_windows` is keyed
 * `(user, period)` to hold one of each at once: a weekly allowance, and a session window that a
 * single sitting is charged against.
 */
@Serializable
enum class WireBudgetPeriod {
    @SerialName("session")
    SESSION,

    @SerialName("week")
    WEEK,
}

/**
 * How much simulation an accounting window allows. Unlimited is a deliberate grant, held by
 * accounts raised by hand, rather than the absence of a limit, so it is its own variant instead of
 * a missing number.
 */
@Serializable
sealed interface SimulationCap {
    @Serializable
    @SerialName("limited")
    data class Limited(val seconds: Double) : SimulationCap

    @Serializable
    @SerialName("unlimited")
    data object Unlimited : SimulationCap
}

/** One accounting window of simulation budget. */
@Serializable
data class BudgetWindow(
    val period: WireBudgetPeriod,
    val usedSeconds: Double,
    val reservedSeconds: Double,
    val cap: SimulationCap,
    val resetsAt: String,
)

@Serializable
data class UserProfile(
    val displayName: String,
    val handle: HandleWire,
    val plan: WirePlan,
    val isAdmin: Boolean,
    val projectCount: Int,
    val budgets: List<BudgetWindow>,
)

@Serializable
data class Invoice(
    val id: String,
    val issuedAt: String,
    val amountEur: Double,
    val paid: Boolean,
)

// renewsAt and paymentMethod join once a billing provider exists; absent fields stay absent.
@Serializable
data class Billing(
    val invoices: List<Invoice>,
)
