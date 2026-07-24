import HomePage from "@/app/page"
import { filterProjects, sampleProjects, useProjectSearch } from "@/lib/projects"
import { theme } from "@/theme/theme"
import { MantineProvider } from "@mantine/core"
import { render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import type { ReactNode } from "react"
import { beforeEach, describe, expect, it, vi } from "vitest"

vi.mock("next/link", () => ({
    default: ({ children, href }: { children?: ReactNode; href?: string }) => <a href={href}>{children}</a>,
}))

describe("filterProjects", () => {
    it("applies the role filter when no search query is set", () => {
        const owned = filterProjects(sampleProjects, "own", "")
        expect(owned.map((project) => project.name)).toEqual(["Datacenter Capacity Study", "Failure Injection Sandbox"])
    })

    it("lets a search query override the role filter", () => {
        const results = filterProjects(sampleProjects, "own", "carbon")
        expect(results.map((project) => project.name)).toEqual(["Carbon-Aware Scheduling"])
    })

    it("ignores a whitespace-only query", () => {
        expect(filterProjects(sampleProjects, "all", "   ")).toHaveLength(sampleProjects.length)
    })
})

describe("project search", () => {
    beforeEach(() => {
        useProjectSearch.setState({ query: "" })
    })

    it("filters the grid and disables the role filter while searching", async () => {
        render(
            <MantineProvider theme={theme}>
                <HomePage />
            </MantineProvider>,
        )
        const user = userEvent.setup()
        await user.type(screen.getByLabelText("Search projects"), "gpu")

        expect(screen.getByText("GPU Cluster Sizing")).toBeInTheDocument()
        expect(screen.queryByText("Datacenter Capacity Study")).not.toBeInTheDocument()
        expect(screen.getByRole("radio", { name: "My projects" })).toBeDisabled()
    })

    it("restores the role filter when the search is cleared", async () => {
        render(
            <MantineProvider theme={theme}>
                <HomePage />
            </MantineProvider>,
        )
        const user = userEvent.setup()
        await user.type(screen.getByLabelText("Search projects"), "gpu")
        await user.click(screen.getByRole("button", { name: "Clear search" }))

        expect(screen.getByText("Datacenter Capacity Study")).toBeInTheDocument()
        expect(screen.getByRole("radio", { name: "My projects" })).toBeEnabled()
    })

    it("reports when nothing matches", async () => {
        render(
            <MantineProvider theme={theme}>
                <HomePage />
            </MantineProvider>,
        )
        const user = userEvent.setup()
        await user.type(screen.getByLabelText("Search projects"), "nonexistent")

        expect(
            screen.getByText("No projects match the current filters or you don't have any projects."),
        ).toBeInTheDocument()
    })
})
