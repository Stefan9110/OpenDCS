"use client"

import { TopologyTable } from "@/components/project/TopologyTable"
import { newTopology } from "@/components/topology/defaults"
import { openNamePrompt } from "@/components/util/NamePrompt"
import { notifyProblem } from "@/components/util/feedback"
import { ApiError, problemFromZod } from "@/lib/api/client"
import { useCreateTopology } from "@/lib/api/topologies"
import type { Id, TopologyTemplate } from "@/lib/api/types"
import { topologySpecSchema } from "@/lib/topology/spec"
import { Button, FileButton, Group, Paper, Stack, Title } from "@mantine/core"
import { IconPlus, IconUpload } from "@tabler/icons-react"

export function TopologySection({ projectId, templates }: { projectId: Id; templates: TopologyTemplate[] }) {
    const create = useCreateTopology(projectId)

    const promptCreate = () =>
        openNamePrompt({
            title: "Create a topology",
            label: "Topology name",
            confirmLabel: "Create",
            onSubmit: (name) => create.mutate({ name, topology: newTopology() }, { onError: notifyProblem }),
        })

    const importFile = async (file: File | null) => {
        if (!file) return
        try {
            const parsed = topologySpecSchema.safeParse(JSON.parse(await file.text()))
            if (!parsed.success) {
                throw new ApiError(problemFromZod(parsed.error, `${file.name} is not a valid topology`))
            }
            create.mutate(
                { name: file.name.replace(/\.json$/i, ""), topology: parsed.data },
                { onError: notifyProblem },
            )
        } catch (error) {
            notifyProblem(error)
        }
    }

    return (
        <Paper withBorder radius="md" p="md">
            <Stack gap="sm">
                <Group justify="space-between">
                    <Title order={4}>Topologies</Title>
                    <Group gap="xs">
                        <FileButton onChange={importFile} accept="application/json">
                            {(props) => (
                                <Button {...props} variant="default" leftSection={<IconUpload size={16} />}>
                                    Import JSON
                                </Button>
                            )}
                        </FileButton>
                        <Button leftSection={<IconPlus size={16} />} onClick={promptCreate} loading={create.isPending}>
                            New topology
                        </Button>
                    </Group>
                </Group>
                <TopologyTable projectId={projectId} templates={templates} />
            </Stack>
        </Paper>
    )
}
