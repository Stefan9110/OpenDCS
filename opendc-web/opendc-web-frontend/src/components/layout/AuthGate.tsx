"use client"

import { MessagePage } from "@/components/layout/MessagePage"
import { notifyComingSoon } from "@/components/util/feedback"
import { useAuth } from "@/lib/auth/auth"
import { Button } from "@mantine/core"
import { IconLogin, IconRefresh } from "@tabler/icons-react"
import type { ReactNode } from "react"

/**
 * Interrupts the page when the session says it must not be shown: nobody is signed in, or the API
 * cannot be reached at all.
 *
 * While the session is still resolving the page renders anyway. Holding it back would put a second
 * skeleton in front of the one the page already draws, and worse, would queue every request behind
 * the session: the projects list would not be asked for until /config and /me had both answered.
 * A visitor who turns out to be signed out sees their page replaced a moment later, which costs
 * nothing, because the pages guard nothing on their own - the server is what refuses to serve
 * somebody else's data.
 */
export function AuthGate({ children }: Readonly<{ children: ReactNode }>) {
    const auth = useAuth()

    if (auth.status === "unavailable") {
        return (
            <MessagePage title="Cannot reach OpenDC" message={auth.problem.title}>
                <Button leftSection={<IconRefresh size={16} />} onClick={auth.retry}>
                    Try again
                </Button>
            </MessagePage>
        )
    }
    if (auth.status === "signedOut") {
        return (
            <MessagePage
                title="Sign in to OpenDC"
                message="Your projects, topologies and experiments live in your account."
            >
                <Button leftSection={<IconLogin size={16} />} onClick={() => notifyComingSoon("Sign in")}>
                    Sign in
                </Button>
            </MessagePage>
        )
    }
    return children
}
