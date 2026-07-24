"use client"

import type {Account} from "@/lib/api/types"
import type {AuthSession} from "@/lib/auth/auth"
import {Avatar, Badge, Group, Stack, Text} from "@mantine/core"
import {IconFolder, IconUser, IconUserHeart, IconUserKey} from "@tabler/icons-react"

export function UserIdentity({session}: Readonly<{ session: AuthSession }>) {
    const authenticated = session.status === "authenticated"
    return (
        <Stack gap="sm">
            <Group gap="sm" wrap="nowrap">
                <Avatar src={session.avatarUrl} alt={session.userName} size="md" radius="xl"/>
                <Stack gap={0}>
                    <Text size="sm" fw={600}>
                        {session.userName}
                    </Text>
                    <Text size="xs" c="dimmed">
                        {authenticated ? session.email : "Not signed in"}
                    </Text>
                </Stack>
            </Group>
            <Group gap="xs">{authenticated ? <AccountBadges account={session.account}/> : <PreviewBadge/>}</Group>
        </Stack>
    )
}

function AccountBadges({account}: Readonly<{ account: Account }>) {
    return (
        <>
            <Badge variant="light" color="opendc" size="sm" leftSection={<IconUser size={12}/>}>
                {account.plan}
            </Badge>
            <Badge variant="light" color="gray" size="sm" leftSection={<IconFolder size={12}/>}>
                {account.projectCount} projects
            </Badge>
            {account.isAdmin &&
                <Badge variant="light" color="red" size="sm" leftSection={<IconUserKey size={12}/>}>
                    Admin
                </Badge>
            }
        </>
    )
}

function PreviewBadge() {
    return (
        <Badge variant="light" color="gray" size="sm">
            Preview session
        </Badge>
    )
}
