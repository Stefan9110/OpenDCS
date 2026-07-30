"use client"

import type { Account } from "@/lib/api/types"
import type { AuthSession } from "@/lib/auth/auth"
import { Avatar, Badge, Group, Stack, Text } from "@mantine/core"
import { IconFolder, IconUser, IconUserKey } from "@tabler/icons-react"

export function UserIdentity({ session }: Readonly<{ session: AuthSession }>) {
    return (
        <Stack gap="sm">
            <Group gap="sm" wrap="nowrap">
                <Avatar src={session.avatarUrl} alt={session.userName} size="md" radius="xl" />
                <Stack gap={0}>
                    <Text size="sm" fw={600}>
                        {session.userName}
                    </Text>
                    <Text size="xs" c="dimmed">
                        {session.email ?? "No email on this account"}
                    </Text>
                </Stack>
            </Group>
            <AccountBadges account={session.account} />
        </Stack>
    )
}

function AccountBadges({ account }: Readonly<{ account: Account }>) {
    return (
        <Group gap="xs">
            <Badge variant="light" color="opendc" size="sm" leftSection={<IconUser size={12} />}>
                {account.plan}
            </Badge>
            <Badge variant="light" color="gray" size="sm" leftSection={<IconFolder size={12} />}>
                {account.projectCount} projects
            </Badge>
            {account.isAdmin && (
                <Badge variant="light" color="red" size="sm" leftSection={<IconUserKey size={12} />}>
                    Admin
                </Badge>
            )}
        </Group>
    )
}
