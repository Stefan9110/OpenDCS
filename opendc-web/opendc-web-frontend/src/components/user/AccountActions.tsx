"use client"

import { notifyComingSoon, notifyProblem } from "@/components/util/feedback"
import { useResetSampleData } from "@/lib/api/projects"
import type { AuthenticatedSession } from "@/lib/auth/auth"
import { Divider, NavLink } from "@mantine/core"
import { useDisclosure } from "@mantine/hooks"
import { IconCreditCard, IconLogout, IconRefresh, IconSettings, IconUserOff } from "@tabler/icons-react"
import { BillingModal } from "./BillingModal"
import { DeactivateAccountModal } from "./DeactivateAccountModal"

export function AccountActions({
    session,
    closeDrawer,
}: Readonly<{ session: AuthenticatedSession; closeDrawer: () => void }>) {
    const reset = useResetSampleData()
    const [billingOpened, billing] = useDisclosure(false)
    const [deactivateOpened, deactivation] = useDisclosure(false)

    function openBilling() {
        closeDrawer()
        billing.open()
    }

    function openDeactivation() {
        closeDrawer()
        deactivation.open()
    }

    return (
        <>
            <NavLink
                component="button"
                label="Billing details"
                leftSection={<IconCreditCard size={16} />}
                onClick={openBilling}
            />
            <NavLink
                component="button"
                label="Log out"
                leftSection={<IconLogout size={16} />}
                onClick={() => notifyComingSoon("Signing out")}
            />
            <NavLink
                component="button"
                label="Reset sample data"
                description="Restores the seeded projects, topologies and experiments"
                leftSection={<IconRefresh size={16} />}
                onClick={() => {
                    closeDrawer()
                    reset.mutate(undefined, { onError: notifyProblem })
                }}
            />
            <Divider my="xs" />
            {session.account.isAdmin && (
                <NavLink component="button" c="red" label="Admin panel" leftSection={<IconSettings size={16} />} />
            )}
            <NavLink
                component="button"
                c="red"
                label="Deactivate account"
                leftSection={<IconUserOff size={16} />}
                onClick={openDeactivation}
            />
            <BillingModal
                plan={session.account.plan}
                billing={session.account.billing}
                opened={billingOpened}
                onClose={billing.close}
            />
            <DeactivateAccountModal
                userName={session.userName}
                opened={deactivateOpened}
                onClose={deactivation.close}
            />
        </>
    )
}
