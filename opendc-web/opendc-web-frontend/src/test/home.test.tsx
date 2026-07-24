import HomePage from "@/app/page"
import { theme } from "@/theme/theme"
import { MantineProvider } from "@mantine/core"
import { Notifications } from "@mantine/notifications"
import { render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import type { ReactNode } from "react"
import { describe, expect, it, vi } from "vitest"

vi.mock("next/link", () => ({
    default: ({ children, href }: { children?: ReactNode; href?: string }) => <a href={href}>{children}</a>,
}))

function renderHome() {
    return render(
        <MantineProvider theme={theme}>
            <Notifications />
            <HomePage />
        </MantineProvider>,
    )
}

describe("home page", () => {
    it("greets the signed-in user and renders the sample projects", () => {
        renderHome()
        expect(screen.getByRole("heading", { name: "Welcome, Ada Lovelace!" })).toBeInTheDocument()
        expect(screen.getByText("Datacenter Capacity Study")).toBeInTheDocument()
    })

    it("shows a preview notice for actions that are not wired up", async () => {
        renderHome()
        const user = userEvent.setup()
        const [createButton] = screen.getAllByRole("button", { name: /create project/i })
        if (!createButton) throw new Error("expected a create-project button")
        await user.click(createButton)
        expect(await screen.findByText(/preview build/i)).toBeInTheDocument()
    })
})
