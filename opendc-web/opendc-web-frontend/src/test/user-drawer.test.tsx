import { UserDrawer } from "@/components/user/UserDrawer"
import { sampleAccount } from "@/lib/account"
import type { AnonymousSession, AuthenticatedSession } from "@/lib/auth/auth"
import { theme } from "@/theme/theme"
import { MantineProvider } from "@mantine/core"
import { cleanup, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, describe, expect, it } from "vitest"

const noop = () => {}

const authenticated: AuthenticatedSession = {
    status: "authenticated",
    userName: "Ada Lovelace",
    avatarUrl: "/img/avatar.svg",
    email: "ada.lovelace@opendc.org",
    account: {
        ...sampleAccount,
        budgets: [
            { period: "session", usedSeconds: 3480, budgetSeconds: 7200, resetsAt: "2026-07-24T14:20:00" },
            { period: "week", usedSeconds: 24480, budgetSeconds: 36000, resetsAt: "2026-07-27T09:00:00" },
        ],
    },
}

const anonymous: AnonymousSession = {
    status: "anonymous",
    userName: "Anonymous",
    avatarUrl: "/img/avatar.svg",
}

// jsdom has no layout engine, so Mantine's overlay transitions leave content at
// display:none and out of the accessibility tree.
function findButton(name: string) {
    return screen.findByRole("button", { name, hidden: true })
}

function openDrawer(session: AuthenticatedSession | AnonymousSession) {
    render(
        <MantineProvider theme={theme} defaultColorScheme="light">
            <UserDrawer opened close={noop} authSession={session} />
        </MantineProvider>,
    )
    return userEvent.setup()
}

afterEach(cleanup)

describe("simulation budget", () => {
    it("shows both windows with their allowance and reset time", () => {
        openDrawer(authenticated)
        expect(screen.getByText("Session")).toBeInTheDocument()
        expect(screen.getByText("5h")).toBeInTheDocument()
        expect(screen.getByText("58 / 120 min")).toBeInTheDocument()
        expect(screen.getByText("Resets at 14:20")).toBeInTheDocument()
        expect(screen.getByText("Weekly")).toBeInTheDocument()
        expect(screen.getByText("7d")).toBeInTheDocument()
        expect(screen.getByText("408 / 600 min")).toBeInTheDocument()
        expect(screen.getByText("Resets Mon 09:00")).toBeInTheDocument()
    })

    it("reports progress on an accessible bar per window", () => {
        openDrawer(authenticated)
        expect(screen.getByLabelText("Session simulation budget")).toBeInTheDocument()
        expect(screen.getByLabelText("Weekly simulation budget")).toBeInTheDocument()
    })
})

describe("account badges", () => {
    it("summarises the plan, project count and membership", () => {
        openDrawer(authenticated)
        expect(screen.getByText("Research")).toBeInTheDocument()
        expect(screen.getByText(`${sampleAccount.projectCount} projects`)).toBeInTheDocument()
        expect(screen.getByText("Since Mar 2026")).toBeInTheDocument()
    })
})

describe("anonymous session", () => {
    it("offers a sign in instead of the account actions", async () => {
        openDrawer(anonymous)
        expect(await findButton("Sign in")).toBeInTheDocument()
        expect(screen.getByText("Preview session")).toBeInTheDocument()
        expect(screen.queryByText("Log out")).not.toBeInTheDocument()
        expect(screen.queryByText("Billing details")).not.toBeInTheDocument()
        expect(screen.queryByText("Deactivate account")).not.toBeInTheDocument()
        expect(screen.queryByText("Simulation budget")).not.toBeInTheDocument()
    })

    it("still allows switching the color scheme", async () => {
        openDrawer(anonymous)
        expect(await findButton("Switch to dark mode")).toBeInTheDocument()
    })
})

describe("deactivate account", () => {
    it("stays disabled until the user name is typed exactly", async () => {
        const user = openDrawer(authenticated)
        await user.click(await findButton("Deactivate account"))

        const confirm = await findButton("Deactivate")
        expect(confirm).toBeDisabled()

        const input = screen.getByLabelText("Type Ada Lovelace to confirm")
        await user.type(input, "ada lovelace")
        expect(confirm).toBeDisabled()

        await user.clear(input)
        await user.type(input, "Ada Lovelace")
        expect(confirm).toBeEnabled()
    })
})

describe("billing details", () => {
    it("shows the plan, payment method and invoices", async () => {
        const user = openDrawer(authenticated)
        await user.click(await findButton("Billing details"))
        expect(await screen.findByText("Research plan")).toBeInTheDocument()
        expect(screen.getByText(sampleAccount.billing.paymentMethod)).toBeInTheDocument()
        expect(screen.getAllByText("Paid")).toHaveLength(sampleAccount.billing.invoices.length)
    })
})
