"use client"

import { BillingModal } from "@/components/user/BillingModal"
import { DeactivateAccountModal } from "@/components/user/DeactivateAccountModal"
import { notifyComingSoon } from "@/components/util/feedback"
import { type AuthSession, useBilling } from "@/lib/auth/auth"
import { Divider, NavLink } from "@mantine/core"
import { useDisclosure } from "@mantine/hooks"
import { IconCreditCard, IconLogout, IconSettings, IconUserOff } from "@tabler/icons-react"

export function AccountActions({ session, closeDrawer }: Readonly<{ session: AuthSession; closeDrawer: () => void }>) {
    const [billingOpened, billing] = useDisclosure(false)
    const [deactivateOpened, deactivation] = useDisclosure(false)
    // Billing is only fetched once the modal that shows it is open.
    const billingQuery = useBilling(billingOpened)

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
                billing={billingQuery.data}
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
