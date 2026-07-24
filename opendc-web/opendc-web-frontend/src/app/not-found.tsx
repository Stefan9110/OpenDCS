"use client"

import { AppShell } from "@/components/layout/AppShell"
import { Button, Center, Space, Stack, Text, Title } from "@mantine/core"
import Link from "next/link"

export default function NotFound() {
    return (
        <AppShell>
            <Center mih="60vh">
                <Stack align="center" gap="md">
                    <Title order={1}>404</Title>
                    <Text c="dimmed">This page does not exist.</Text>
                    <Space h="md" />
                    <Button component={Link} href="/">
                        Back to projects
                    </Button>
                </Stack>
            </Center>
        </AppShell>
    )
}
