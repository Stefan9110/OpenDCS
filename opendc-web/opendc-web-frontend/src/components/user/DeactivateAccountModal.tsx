"use client"

import {notifyComingSoon} from "@/lib/notify"
import {Alert, Button, Group, Modal, Stack, Text, TextInput} from "@mantine/core"
import {IconAlertTriangle} from "@tabler/icons-react"
import {useState} from "react"

export function DeactivateAccountModal({
                                           userName,
                                           opened,
                                           onClose,
                                       }: Readonly<{ userName: string; opened: boolean; onClose: () => void }>) {
    const [confirmation, setConfirmation] = useState("")

    function close() {
        setConfirmation("")
        onClose()
    }

    function deactivate() {
        notifyComingSoon("Deactivating an account")
        close()
    }

    return (
        <Modal
            opened={opened}
            onClose={close}
            title="Deactivate account"
            size="md"
            centered
        >
            <Stack gap="md">
                <Alert color="red" variant="light" icon={<IconAlertTriangle size="md"/>}>
                    Your projects, topologies and experiment results are deleted <b>permanently</b>. This cannot be
                    undone.
                </Alert>
                <TextInput
                    label={<Text size={"xs"}>Type <i>{userName}</i> to confirm</Text>}
                    value={confirmation}
                    onChange={(event) => setConfirmation(event.currentTarget.value)}
                />
                <Group justify="flex-end" gap="sm">
                    <Button variant="default" onClick={close}>
                        Cancel
                    </Button>
                    <Button color="red" disabled={confirmation !== userName} onClick={deactivate}>
                        Deactivate
                    </Button>
                </Group>
            </Stack>
        </Modal>
    )
}
