"use client"

import { formatCount, formatSimulationBudget } from "@/components/format"
import { openNamePrompt } from "@/components/util/NamePrompt"
import { notifyProblem } from "@/components/util/feedback"
import {
    useCancelExperiment,
    useCloneExperiment,
    useDeleteExperiment,
    useSaveExperimentDraft,
    useSubmitExperiment,
} from "@/lib/api/experiments"
import type { Experiment } from "@/lib/api/types"
import { isTerminalExperiment } from "@/lib/experiment/status"
import { ActionIcon, Button, Group, Menu, Text } from "@mantine/core"
import { modals } from "@mantine/modals"
import {
    IconCopy,
    IconDots,
    IconDownload,
    IconPencil,
    IconPlayerPlay,
    IconPlayerStop,
    IconTrash,
} from "@tabler/icons-react"
import { useRouter } from "next/navigation"

export function ExperimentActions({ projectId, experiment }: { projectId: number; experiment: Experiment }) {
    const submit = useSubmitExperiment(projectId)
    const cancel = useCancelExperiment(projectId)
    const clone = useCloneExperiment(projectId)
    const rename = useSaveExperimentDraft(projectId, experiment.id)
    const remove = useDeleteExperiment(projectId)
    const router = useRouter()

    const isDraft = experiment.state === "draft"
    const isRunning = !isDraft && !isTerminalExperiment(experiment.state)

    const confirmSubmit = () =>
        modals.openConfirmModal({
            title: `Run ${experiment.name}?`,
            children: (
                <Text size="sm">
                    {formatCount(experiment.estimate.scenarioCount)} scenarios will be queued, reserving about{" "}
                    {formatSimulationBudget(experiment.estimate.estimatedBudgetSeconds)} of budget. The experiment is
                    frozen once it starts.
                </Text>
            ),
            labels: { confirm: "Run experiment", cancel: "Keep editing" },
            onConfirm: () => submit.mutate(experiment.id, { onError: notifyProblem }),
        })

    const confirmCancel = () =>
        modals.openConfirmModal({
            title: `Stop ${experiment.name}?`,
            children: <Text size="sm">Scenarios that already finished keep their results. The rest are dropped.</Text>,
            labels: { confirm: "Stop run", cancel: "Let it finish" },
            confirmProps: { color: "red" },
            onConfirm: () => cancel.mutate(experiment.id, { onError: notifyProblem }),
        })

    const confirmDelete = () =>
        modals.openConfirmModal({
            title: `Delete ${experiment.name}?`,
            children: <Text size="sm">Its results and scenario history are removed with it.</Text>,
            labels: { confirm: "Delete experiment", cancel: "Keep it" },
            confirmProps: { color: "red" },
            onConfirm: () =>
                remove.mutate(experiment.id, {
                    onSuccess: () => router.push(`/project?id=${projectId}`),
                    onError: notifyProblem,
                }),
        })

    const promptRename = () =>
        openNamePrompt({
            title: "Rename experiment",
            label: "Experiment name",
            initial: experiment.name,
            confirmLabel: "Rename",
            onSubmit: (name) => rename.mutate({ name, spec: experiment.spec }, { onError: notifyProblem }),
        })

    return (
        <Group gap="xs" wrap="nowrap">
            {isDraft && (
                <Button
                    leftSection={<IconPlayerPlay size={16} />}
                    onClick={confirmSubmit}
                    loading={submit.isPending}
                    disabled={experiment.estimate.scenarioCount === 0}
                >
                    Run experiment
                </Button>
            )}
            {isRunning && (
                <Button
                    variant="light"
                    color="red"
                    leftSection={<IconPlayerStop size={16} />}
                    onClick={confirmCancel}
                    loading={cancel.isPending}
                >
                    Stop
                </Button>
            )}

            <Menu position="bottom-end">
                <Menu.Target>
                    <ActionIcon variant="subtle" color="gray" size="lg" aria-label="Experiment actions">
                        <IconDots size={18} />
                    </ActionIcon>
                </Menu.Target>
                <Menu.Dropdown>
                    {isDraft && (
                        <Menu.Item leftSection={<IconPencil size={16} />} onClick={promptRename}>
                            Rename
                        </Menu.Item>
                    )}
                    <Menu.Item
                        leftSection={<IconCopy size={16} />}
                        onClick={() =>
                            clone.mutate(experiment.id, {
                                onSuccess: (draft) =>
                                    router.push(`/experiment?project=${projectId}&experiment=${draft.id}`),
                                onError: notifyProblem,
                            })
                        }
                    >
                        Clone to draft
                    </Menu.Item>
                    <Menu.Item leftSection={<IconDownload size={16} />} onClick={() => downloadSpec(experiment)}>
                        Export configuration
                    </Menu.Item>
                    <Menu.Divider />
                    <Menu.Item color="red" leftSection={<IconTrash size={16} />} onClick={confirmDelete}>
                        Delete
                    </Menu.Item>
                </Menu.Dropdown>
            </Menu>
        </Group>
    )
}

function downloadSpec(experiment: Experiment): void {
    const blob = new Blob([JSON.stringify(experiment.spec, null, 4)], { type: "application/json" })
    const url = URL.createObjectURL(blob)
    const link = document.createElement("a")
    link.href = url
    link.download = `${experiment.name.replaceAll(/\s+/g, "-").toLowerCase()}.json`
    link.click()
    URL.revokeObjectURL(url)
}
