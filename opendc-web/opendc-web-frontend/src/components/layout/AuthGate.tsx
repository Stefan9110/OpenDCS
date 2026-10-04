"use client"

import { MessagePage } from "@/components/layout/MessagePage"
import { OnboardingPage } from "@/components/user/OnboardingPage"
import { useAuth } from "@/lib/auth/auth"
import { useSessionControls } from "@/lib/auth/session"
import { Button } from "@mantine/core"
import { IconLogin, IconLogout, IconRefresh } from "@tabler/icons-react"
import type { ReactNode } from "react"

/**
 * Interrupts the page when the session says it must not be shown: nobody is signed in, the account
 * is deactivated or still has to choose a handle, or the API cannot be reached at all.
 *
 * While the session is still resolving the page renders anyway. Holding it back would put a second
 * skeleton in front of the one the page already draws, and would queue every request behind the
 * session. The pages guard nothing on their own: the server is what refuses somebody else's data.
 */
export function AuthGate({ children }: Readonly<{ children: ReactNode }>) {
    const auth = useAuth()
    const controls = useSessionControls()

    switch (auth.status) {
        case "unavailable":
            return (
                <MessagePage title="Cannot reach OpenDC" message={auth.problem.title}>
                    <Button leftSection={<IconRefresh size={16} />} onClick={auth.retry}>
                        Try again
                    </Button>
                </MessagePage>
            )
        case "signedOut":
            return (
                <MessagePage
                    title="Sign in to OpenDC"
                    message="Your projects, topologies and experiments live in your account."
                >
                    {controls.type === "auth0" && (
                        <Button leftSection={<IconLogin size={16} />} onClick={controls.signIn}>
                            Sign in
                        </Button>
                    )}
                </MessagePage>
            )
        case "deactivated":
            return (
                <MessagePage
                    title="This account has been deactivated"
                    message="It can no longer use OpenDC. Projects you shared with others stay with them."
                >
                    {controls.type === "auth0" && (
                        <Button leftSection={<IconLogout size={16} />} variant="default" onClick={controls.signOut}>
                            Sign out
                        </Button>
                    )}
                </MessagePage>
            )
        case "needsHandle":
            return <OnboardingPage />
        case "loading":
        case "signedIn":
            return children
    }
}
