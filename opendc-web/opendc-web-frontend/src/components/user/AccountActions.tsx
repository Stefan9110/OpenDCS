"use client"

import type {AuthenticatedSession} from "@/lib/auth/auth"
import {notifyComingSoon} from "@/lib/notify"
import {Divider, NavLink} from "@mantine/core"
import {useDisclosure} from "@mantine/hooks"
import {IconCreditCard, IconLogout, IconSettings, IconUserOff} from "@tabler/icons-react"
import {BillingModal} from "./BillingModal"
import {DeactivateAccountModal} from "./DeactivateAccountModal"

export function AccountActions({
                                   session,
                                   closeDrawer,
                               }: Readonly<{ session: AuthenticatedSession; closeDrawer: () => void }>) {
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
                leftSection={<IconCreditCard size={16}/>}
                onClick={openBilling}
            />
            <NavLink
                component="button"
                label="Log out"
                leftSection={<IconLogout size={16}/>}
                onClick={() => notifyComingSoon("Signing out")}
            />
            <Divider my="xs"/>
            {session.account.isAdmin && <NavLink
                component="button"
                c="red"
                label="Admin panel"
                leftSection={<IconSettings size={16}/>}
            />}
            <NavLink
                component="button"
                c="red"
                label="Deactivate account"
                leftSection={<IconUserOff size={16}/>}
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
