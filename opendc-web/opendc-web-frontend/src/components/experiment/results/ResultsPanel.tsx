"use client"

import { HeadlineStats } from "@/components/experiment/results/HeadlineStats"
import { MetricChart } from "@/components/experiment/results/MetricChart"
import { RunPicker } from "@/components/experiment/results/RunPicker"
import { ScenarioComparison } from "@/components/experiment/results/ScenarioComparison"
import {
    MAX_OVERLAID_RUNS,
    describeRuns,
    formatInterval,
    formatSimulatedDuration,
    metricOptions,
    resultsCsv,
} from "@/components/experiment/results/resultsView"
import { QueryState } from "@/components/util/QueryState"
import { useExperimentResults } from "@/lib/api/experiments"
import type { Experiment } from "@/lib/api/types"
import {
    type ExperimentResults,
    type MetricId,
    RESULT_METRICS,
    type ResultMetric,
    reportedMetrics,
    simulatedSpan,
} from "@/lib/experiment/results"
import { ActionIcon, Alert, Button, Group, Paper, Select, Stack, Text, Title, Tooltip } from "@mantine/core"
import { IconDownload, IconFlask, IconInfoCircle } from "@tabler/icons-react"
import { type ReactNode, useState } from "react"

export function ResultsPanel({ projectId, experiment }: { projectId: number; experiment: Experiment }) {
    const results = useExperimentResults(projectId, experiment.id)

    if (experiment.state === "draft") {
        return (
            <Alert color="gray" icon={<IconFlask size={18} />} title="Nothing has run yet">
                Results appear here scenario by scenario once the experiment runs.
            </Alert>
        )
    }

    return (
        <QueryState query={results} loadingLabel="Loading results">
            {(loaded) => <LoadedResults experiment={experiment} results={loaded} />}
        </QueryState>
    )
}

function LoadedResults({ experiment, results }: { experiment: Experiment; results: ExperimentResults }) {
    const reported = reportedMetrics(results)
    const [preferred, setPreferred] = useState<MetricId>("host.cpu_utilization")
    const [chosen, setChosen] = useState<number[]>([])

    if (reported.length === 0) {
        return (
            <Alert color="gray" icon={<IconFlask size={18} />} title="Waiting for the first samples">
                Every scenario is still queued. Measurements stream in at the export interval once one starts.
            </Alert>
        )
    }

    const metric = pickMetric(reported, preferred)
    const shown = pickRuns(results, chosen)
    const runs = results.scenarios.filter((scenario) => shown.includes(scenario.scenarioIndex))
    const labels = describeRuns(
        experiment.spec,
        runs.map((run) => run.scenarioIndex),
    )
    const primary = runs[0]
    const spanMs = simulatedSpan(results)

    return (
        <Stack gap="md">
            <Group justify="space-between" wrap="wrap" gap="sm">
                <Group gap="sm" wrap="wrap">
                    <Select
                        data={metricOptions(results)}
                        value={metric.id}
                        onChange={(value) => setPreferred(metricIdOf(value, metric.id))}
                        allowDeselect={false}
                        w={220}
                        aria-label="Metric"
                    />
                    <RunPicker
                        spec={experiment.spec}
                        scenarios={results.scenarios}
                        chosen={shown}
                        onChange={setChosen}
                    />
                </Group>
                <Button
                    variant="default"
                    leftSection={<IconDownload size={16} />}
                    onClick={() => downloadCsv(experiment.name, results)}
                >
                    Export CSV
                </Button>
            </Group>

            {primary && (
                <Stack gap={6}>
                    <Text size="xs" c="dimmed">
                        {labels[0]?.short} totals
                    </Text>
                    <HeadlineStats run={primary} />
                </Stack>
            )}

            <ChartCard
                title={`${metric.label} over simulated time`}
                caption={`${formatSimulatedDuration(spanMs)} simulated, one point per ${formatInterval(results.bucketMs)}`}
                hint={metric.description}
            >
                <MetricChart runs={runs} labels={labels} metric={metric.id} spanMs={spanMs} />
            </ChartCard>

            {results.scenarios.length > 1 && (
                <ChartCard
                    title={`${metric.label} per scenario`}
                    caption={`All ${results.scenarios.length} scenarios, reduced over each whole run`}
                    hint={`Every scenario reduced to a single number by taking the ${metric.reduce} of its samples.`}
                >
                    <ScenarioComparison spec={experiment.spec} scenarios={results.scenarios} metric={metric.id} />
                </ChartCard>
            )}
        </Stack>
    )
}

function ChartCard({
    title,
    caption,
    hint,
    children,
}: {
    title: string
    caption: string
    hint: string
    children: ReactNode
}) {
    return (
        <Paper withBorder radius="md" p="md">
            <Stack gap="sm">
                <Stack gap={2}>
                    <Group gap={6}>
                        <Title order={5} fw={500}>
                            {title}
                        </Title>
                        <Tooltip label={hint} withArrow multiline w={280}>
                            <ActionIcon variant="subtle" color="gray" size="xs" aria-label={`About ${title}`}>
                                <IconInfoCircle size={14} />
                            </ActionIcon>
                        </Tooltip>
                    </Group>
                    <Text size="xs" c="dimmed">
                        {caption}
                    </Text>
                </Stack>
                {children}
            </Stack>
        </Paper>
    )
}

function pickMetric(reported: ResultMetric[], preferred: MetricId): ResultMetric {
    const chosen = reported.find((metric) => metric.id === preferred) ?? reported[0]
    if (!chosen) throw new Error("no metric to show")
    return chosen
}

function metricIdOf(raw: string | null, fallback: MetricId): MetricId {
    return RESULT_METRICS.find((metric) => metric.id === raw)?.id ?? fallback
}

function pickRuns(results: ExperimentResults, chosen: number[]): number[] {
    const available = results.scenarios.map((scenario) => scenario.scenarioIndex)
    const kept = chosen.filter((index) => available.includes(index))
    return kept.length > 0 ? kept : available.slice(0, MAX_OVERLAID_RUNS)
}

function downloadCsv(name: string, results: ExperimentResults): void {
    const blob = new Blob([resultsCsv(results)], { type: "text/csv" })
    const url = URL.createObjectURL(blob)
    const link = document.createElement("a")
    link.href = url
    link.download = `${name.replaceAll(/\s+/g, "-").toLowerCase()}-results.csv`
    link.click()
    URL.revokeObjectURL(url)
}
