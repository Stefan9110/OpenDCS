"use client"

import { Auth0Session } from "@/components/layout/Auth0Session"
import { MessagePage } from "@/components/layout/MessagePage"
import { problemOf } from "@/lib/api/client"
import { useDeploymentConfig } from "@/lib/api/config"
import { Button, Center, Loader } from "@mantine/core"
import { IconRefresh } from "@tabler/icons-react"
import type { ReactNode } from "react"

/**
 * Sets up signing in the way this deployment asks for, read from the server at runtime, so one
 * build of the app serves every deployment. Nothing below renders until that is known, since every
 * request has to know whether it carries a token.
 */
export function AuthProvider({ children }: Readonly<{ children: ReactNode }>) {
    const config = useDeploymentConfig()

    if (config.isPending) {
        return (
            <Center h="100vh">
                <Loader />
            </Center>
        )
    }
    if (config.isError) {
        return (
            <MessagePage title="Cannot reach OpenDC" message={problemOf(config.error).title}>
                <Button leftSection={<IconRefresh size={16} />} onClick={() => void config.refetch()}>
                    Try again
                </Button>
            </MessagePage>
        )
    }
    const auth = config.data.auth
    switch (auth.type) {
        case "anonymous":
            return children
        case "auth0":
            return <Auth0Session settings={auth}>{children}</Auth0Session>
    }
}
