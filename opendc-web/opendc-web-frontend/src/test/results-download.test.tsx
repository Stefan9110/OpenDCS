import { ResultsPanel } from "@/components/experiment/results/ResultsPanel"
import { resultsArchiveUrl } from "@/lib/api/experiments"
import type { Experiment } from "@/lib/api/types"
import type { ExperimentResults } from "@/lib/experiment/results"
import type { ExperimentState } from "@/lib/experiment/status"
import { theme } from "@/theme/theme"
import { MantineProvider } from "@mantine/core"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

const EXPERIMENT_ID = "8f2b6b3e-58cd-4c04-9a8e-1b8f4b1a2c33"

const RESULTS: ExperimentResults = {
    experimentId: EXPERIMENT_ID,
    exportIntervalMs: 300_000,
    bucketMs: 300_000,
    complete: true,
    scenarios: [
        {
            scenarioIndex: 0,
            seeds: 1,
            complete: true,
            series: [{ metric: "host.cpu_utilization", points: [{ t: 0, value: 0.4 }], spread: 0 }],
        },
    ],
}

function experiment(state: ExperimentState): Experiment {
    return {
        id: EXPERIMENT_ID,
        projectId: "3f5b1f4a-7c8e-4c3d-9d5a-2f9a1c7b6d21",
        name: "Nightly sweep",
        state,
        spec: {
            topologies: [
                {
                    clusters: [
                        {
                            name: "a",
                            hosts: [{ cpu: { coreCount: 8, coreSpeed: "3 GHz" }, memory: { size: "64 GiB" } }],
                        },
                    ],
                },
            ],
            workloads: [{ type: "trace", source: { type: "named", name: "bitbrains-small" } }],
        },
        specHash: "hash",
        estimate: { scenarioCount: 1, estimatedSimulationSeconds: 1, estimatedBudgetSeconds: 1 },
        createdAt: "2026-08-01T00:00:00Z",
        updatedAt: "2026-08-01T00:00:00Z",
        submittedAt: "2026-08-01T00:00:00Z",
    }
}

function show(state: ExperimentState, results: ExperimentResults) {
    vi.stubGlobal(
        "fetch",
        vi.fn(
            async () =>
                new Response(JSON.stringify(results), {
                    status: 200,
                    headers: { "Content-Type": "application/json" },
                }),
        ),
    )
    render(
        <QueryClientProvider client={new QueryClient()}>
            <MantineProvider theme={theme} defaultColorScheme="light">
                <ResultsPanel experiment={experiment(state)} />
            </MantineProvider>
        </QueryClientProvider>,
    )
}

afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
})

/**
 * The parquet an experiment produces is the result; the chart is a reduction of it. Taking the files
 * away has to be reachable from the results view, and has to be a link so the archive streams to disk
 * rather than being assembled in the tab.
 */
describe("downloading an experiment's results", () => {
    it("offers the whole output tree as a link, so a large archive never passes through memory", async () => {
        show("succeeded", RESULTS)

        const link = await screen.findByRole("link", { name: "Download all results" })

        expect(link).toHaveAttribute("href", resultsArchiveUrl(EXPERIMENT_ID))
    })

    it("offers the archive before any samples have arrived, since the files land run by run", async () => {
        show("running", { ...RESULTS, complete: false, scenarios: [] })

        expect(await screen.findByRole("link", { name: "Download all results" })).toBeInTheDocument()
        expect(screen.queryByRole("button", { name: "Export CSV" })).not.toBeInTheDocument()
    })

    it("says nothing about downloading an experiment that has not started, which has written nothing", async () => {
        show("queued", { ...RESULTS, complete: false, scenarios: [] })

        await screen.findByText(/Waiting for the first samples/)
        expect(screen.queryByRole("link", { name: "Download all results" })).not.toBeInTheDocument()
    })

    it("does not offer results for a draft, which has never run", () => {
        show("draft", RESULTS)

        expect(screen.queryByRole("link", { name: "Download all results" })).not.toBeInTheDocument()
    })
})
