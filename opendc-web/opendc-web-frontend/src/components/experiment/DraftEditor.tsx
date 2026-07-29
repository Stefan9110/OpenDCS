"use client"

import { AxisSelect } from "@/components/experiment/AxisSelect"
import { AXIS_LABELS } from "@/components/experiment/axisLabels"
import { bindFailureModels, bindSchedulers, bindTopologies, bindWorkloads } from "@/components/experiment/draftAxes"
import { formatCount, formatSimulationBudget } from "@/components/format"
import { notifyProblem } from "@/components/util/feedback"
import { useCatalog } from "@/lib/api/catalogs"
import { useSaveExperimentDraft } from "@/lib/api/experiments"
import { useTopologies } from "@/lib/api/topologies"
import type { Experiment } from "@/lib/api/types"
import {
    DEFAULT_RUNS,
    type ExperimentSpec,
    allocationPolicyAxis,
    experimentRuns,
    failureModelAxis,
    scenarioCount,
} from "@/lib/experiment/spec"
import { Divider, Group, NumberInput, Paper, Stack, Text } from "@mantine/core"
import { useDebouncedCallback } from "@mantine/hooks"
import { useState } from "react"

const SAVE_DELAY_MS = 600

export function DraftEditor({ projectId, experiment }: { projectId: number; experiment: Experiment }) {
    const [spec, setSpec] = useState(experiment.spec)
    const save = useSaveExperimentDraft(projectId, experiment.id)
    const templates = useTopologies(projectId)
    const traces = useCatalog("traces")
    const schedulers = useCatalog("schedulers")
    const prefabs = useCatalog("failure-prefabs")

    const persist = useDebouncedCallback(
        (next: ExperimentSpec) => save.mutate({ name: experiment.name, spec: next }, { onError: notifyProblem }),
        SAVE_DELAY_MS,
    )

    const edit = (change: (current: ExperimentSpec) => ExperimentSpec) =>
        setSpec((current) => {
            const next = change(current)
            persist(next)
            return next
        })

    const topologies = bindTopologies(spec.topologies, templates.data ?? [])
    const workloads = bindWorkloads(spec.workloads, traces.data ?? [])
    const allocation = bindSchedulers(allocationPolicyAxis(spec), schedulers.data ?? [])
    const failures = bindFailureModels(failureModelAxis(spec), prefabs.data ?? [])

    return (
        <Paper withBorder radius="md" p="md">
            <Stack gap="md">
                <AxisSelect
                    label={AXIS_LABELS.topologies}
                    choices={topologies.choices}
                    value={topologies.selected}
                    onChange={(values) => edit((current) => ({ ...current, topologies: topologies.rebuild(values) }))}
                />
                <AxisSelect
                    label={AXIS_LABELS.workloads}
                    choices={workloads.choices}
                    value={workloads.selected}
                    onChange={(values) => edit((current) => ({ ...current, workloads: workloads.rebuild(values) }))}
                />
                <AxisSelect
                    label={AXIS_LABELS.allocationPolicies}
                    choices={allocation.choices}
                    value={allocation.selected}
                    onChange={(values) =>
                        edit((current) => ({ ...current, allocationPolicies: allocation.rebuild(values) }))
                    }
                />
                <AxisSelect
                    label={AXIS_LABELS.failureModels}
                    choices={failures.choices}
                    value={failures.selected}
                    onChange={(values) => edit((current) => ({ ...current, failureModels: failures.rebuild(values) }))}
                />

                <NumberInput
                    label="Runs per scenario"
                    description="Repeats with a different seed, averaged in the results"
                    min={1}
                    max={32}
                    value={experimentRuns(spec)}
                    onChange={(value) =>
                        edit((current) => ({ ...current, runs: typeof value === "number" ? value : DEFAULT_RUNS }))
                    }
                />

                <Divider my={4} />

                <Group justify="space-between" wrap="nowrap" gap="sm">
                    <Text size="sm" c="dimmed">
                        {formatCount(scenarioCount(spec))} scenarios, about{" "}
                        {formatSimulationBudget(experiment.estimate.estimatedBudgetSeconds)}
                    </Text>
                    <Text size="xs" c="dimmed">
                        {save.isPending ? "Saving" : "Saved"}
                    </Text>
                </Group>
            </Stack>
        </Paper>
    )
}
