"use client"

import { UserDrawer } from "@/components/user/UserDrawer"
import { useAuth } from "@/lib/auth/auth"
import { Avatar, Button, Skeleton, Text } from "@mantine/core"
import { useDisclosure } from "@mantine/hooks"

export function UserMenu() {
    const auth = useAuth()
    const [opened, { open, close }] = useDisclosure(false)

    if (auth.status === "loading") {
        return <Skeleton height={32} width={130} radius="lg" />
    }
    // Signed out, or the API is unreachable: either way the gate is explaining it in the main area
    // and there is no account here to open.
    if (auth.status !== "signedIn") {
        return undefined
    }

    const { userName, avatarUrl } = auth.session
    return (
        <>
            <UserDrawer opened={opened} close={close} session={auth.session} />
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
