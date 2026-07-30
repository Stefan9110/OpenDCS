"use client"

import { ExperimentTable } from "@/components/project/ExperimentTable"
import { openNamePrompt } from "@/components/util/NamePrompt"
import { notifyProblem } from "@/components/util/feedback"
import { useCreateExperiment } from "@/lib/api/experiments"
import type { ExperimentSummary, Id, TopologyTemplate } from "@/lib/api/types"
import type { ExperimentSpec } from "@/lib/experiment/spec"
import { Button, Group, Paper, Stack, Title, Tooltip } from "@mantine/core"
import { IconPlus } from "@tabler/icons-react"
import { useRouter } from "next/navigation"

export function ExperimentSection({
    projectId,
    experiments,
    templates,
}: {
    projectId: Id
    experiments: ExperimentSummary[]
    templates: TopologyTemplate[]
}) {
    const create = useCreateExperiment(projectId)
    const router = useRouter()
    const first = templates[0]

    const promptCreate = () => {
        if (!first) return
        const spec: ExperimentSpec = {
            topologies: [first.topology],
            workloads: [{ type: "trace", source: { type: "named", name: "bitbrains-small" } }],
        }
        openNamePrompt({
            title: "Create an experiment",
            label: "Experiment name",
            confirmLabel: "Create draft",
            onSubmit: (name) =>
                create.mutate(
                    { name, spec },
                    {
                        onSuccess: (experiment) => router.push(`/experiment?id=${experiment.id}`),
                        onError: notifyProblem,
                    },
                ),
        })
    }

    return (
        <Paper withBorder radius="md" p="md">
            <Stack gap="sm">
                <Group justify="space-between">
                    <Title order={4}>Experiments</Title>
                    <Tooltip label="Create a topology first" disabled={first !== undefined}>
                        <Button
                            leftSection={<IconPlus size={16} />}
                            onClick={promptCreate}
                            disabled={first === undefined}
                            loading={create.isPending}
                        >
                            New experiment
                        </Button>
                    </Tooltip>
                </Group>
                <ExperimentTable projectId={projectId} experiments={experiments} />
            </Stack>
        </Paper>
    )
}
