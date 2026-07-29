"use client"

import { AxisSummary } from "@/components/experiment/AxisSummary"
import { DraftEditor } from "@/components/experiment/DraftEditor"
import { RunProgress } from "@/components/experiment/RunProgress"
import { ScenarioTable } from "@/components/experiment/ScenarioTable"
import { QueryState } from "@/components/util/QueryState"
import { useExperimentStatus } from "@/lib/api/experiments"
import type { Experiment } from "@/lib/api/types"
import { Grid, Stack } from "@mantine/core"

export function ExperimentOverview({ projectId, experiment }: { projectId: number; experiment: Experiment }) {
    const status = useExperimentStatus(projectId, experiment.id)
    const isDraft = experiment.state === "draft"

    return (
        <Grid gutter="md">
            <Grid.Col span={{ base: 12, lg: 8 }}>
                <QueryState query={status} loadingLabel="Loading run status">
                    {(loaded) => (
                        <Stack gap="md">
                            {!isDraft && <RunProgress experiment={experiment} status={loaded} />}
                            <ScenarioTable spec={experiment.spec} statuses={loaded.scenarios} />
                        </Stack>
                    )}
                </QueryState>
            </Grid.Col>
            <Grid.Col span={{ base: 12, lg: 4 }}>
                {isDraft ? (
                    <DraftEditor projectId={projectId} experiment={experiment} />
                ) : (
                    <AxisSummary spec={experiment.spec} estimate={experiment.estimate} />
                )}
            </Grid.Col>
        </Grid>
    )
}
