"use client"

import { AxisSummary } from "@/components/experiment/AxisSummary"
import { DraftEditor } from "@/components/experiment/DraftEditor"
import { RunProgress } from "@/components/experiment/RunProgress"
import { ScenarioTable } from "@/components/experiment/ScenarioTable"
import { TableGhost } from "@/components/util/Ghost"
import { QueryState } from "@/components/util/QueryState"
import { notifyProblem } from "@/components/util/feedback"
import { useExperimentStatus, useRetryScenario } from "@/lib/api/experiments"
import type { Experiment } from "@/lib/api/types"
import { Grid, Stack } from "@mantine/core"

export function ExperimentOverview({ experiment }: { experiment: Experiment }) {
    const status = useExperimentStatus(experiment.id)
    const retry = useRetryScenario(experiment.id)
    const isDraft = experiment.state === "draft"

    return (
        <Grid gutter="md">
            <Grid.Col span={{ base: 12, lg: 8 }}>
                <QueryState query={status} ghost={<TableGhost columns={5} rows={4} />}>
                    {(loaded) => (
                        <Stack gap="md">
                            {!isDraft && <RunProgress experiment={experiment} status={loaded} />}
                            <ScenarioTable
                                spec={experiment.spec}
                                statuses={loaded.scenarios}
                                onRetry={
                                    isDraft
                                        ? undefined
                                        : (scenarioIndex) => retry.mutate(scenarioIndex, { onError: notifyProblem })
                                }
                            />
                        </Stack>
                    )}
                </QueryState>
            </Grid.Col>
            <Grid.Col span={{ base: 12, lg: 4 }}>
                {isDraft ? (
                    <DraftEditor experiment={experiment} />
                ) : (
                    <AxisSummary spec={experiment.spec} estimate={experiment.estimate} />
                )}
            </Grid.Col>
        </Grid>
    )
}
