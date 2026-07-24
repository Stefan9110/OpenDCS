"use client"

import { UserDrawer } from "@/components/user/UserDrawer"
import { useAuth } from "@/lib/auth/auth"
import { Avatar, Button, Text } from "@mantine/core"
import { useDisclosure } from "@mantine/hooks"

export function UserMenu() {
    const authSession = useAuth()
    const { userName, avatarUrl } = authSession
    const [opened, { open, close }] = useDisclosure(false)
    return (
        <>
            <UserDrawer opened={opened} close={close} authSession={authSession} />
            <Button
                variant="subtle"
                color="gray"
                radius="lg"
                c="white"
                aria-label="Open account menu"
                leftSection={<Avatar src={avatarUrl} alt={userName} size="sm" radius="xl" />}
                onClick={open}
            >
                <Text size="sm" visibleFrom="sm">
                    {userName}
                </Text>
            </Button>
        </>
    )
}
