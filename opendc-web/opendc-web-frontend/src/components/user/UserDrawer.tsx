"use client"

import { AccountActions } from "@/components/user/AccountActions"
import { SimulationBudget } from "@/components/user/SimulationBudget"
import { UserIdentity } from "@/components/user/UserIdentity"
import { notifyComingSoon } from "@/components/util/feedback"
import type { AuthSession } from "@/lib/auth/auth"
import {
    Button,
    Divider,
    Drawer,
    NavLink,
    Stack,
    Text,
    useComputedColorScheme,
    useMantineColorScheme,
} from "@mantine/core"
import { IconLogin, IconMoon, IconSun } from "@tabler/icons-react"

export function UserDrawer({
    opened,
    close,
    authSession,
}: Readonly<{ opened: boolean; close: () => void; authSession: AuthSession }>) {
    const authenticated = authSession.status === "authenticated"
    return (
        <Drawer opened={opened} onClose={close} position="right" size="sm" title="Account" keepMounted>
            <Stack gap="md">
                <UserIdentity session={authSession} />
                <Divider />
                {authenticated ? <SimulationBudget budgets={authSession.account.budgets} /> : <SignInNotice />}
                <Divider />
                <Stack gap={0}>
                    <ColorSchemeItem />
                    {authenticated && <AccountActions session={authSession} closeDrawer={close} />}
                </Stack>
            </Stack>
        </Drawer>
    )
}

function ColorSchemeItem() {
    const { setColorScheme } = useMantineColorScheme()
    const computed = useComputedColorScheme("light", { getInitialValueInEffect: true })
    const isDark = computed === "dark"
    return (
        <NavLink
            component="button"
            label={isDark ? "Light mode" : "Dark mode"}
            leftSection={isDark ? <IconSun size={16} /> : <IconMoon size={16} />}
            onClick={() => setColorScheme(isDark ? "light" : "dark")}
            aria-label={isDark ? "Switch to light mode" : "Switch to dark mode"}
        />
    )
}

function SignInNotice() {
    return (
        <Stack gap="sm">
            <Text size="sm" c="dimmed">
                Sign in to track your simulation budget and keep your projects.
            </Text>
            <Button leftSection={<IconLogin size={16} />} onClick={() => notifyComingSoon("Sign in")}>
                Sign in
            </Button>
        </Stack>
    )
}
