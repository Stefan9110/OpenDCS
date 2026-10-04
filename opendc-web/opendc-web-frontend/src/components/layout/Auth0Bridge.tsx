"use client"

import { setAuthHeaders } from "@/lib/api/client"
import { type SessionControls, SessionProvider } from "@/lib/auth/session"
import { config } from "@/lib/config"
import { useAuth0 } from "@auth0/auth0-react"
import { Center, Loader } from "@mantine/core"
import { useQueryClient } from "@tanstack/react-query"
import { type ReactNode, useEffect, useState } from "react"

/**
 * Hands the API client its access tokens once Auth0 knows who is signed in, and gives the rest of
 * the app the controls to sign in and out. Children wait until the tokens are wired, or their first
 * requests would go out without one and read as signed out.
 */
export function Auth0Bridge({ children }: Readonly<{ children: ReactNode }>) {
    const { isLoading, isAuthenticated, getAccessTokenSilently, loginWithRedirect, logout, user } = useAuth0()
    const queryClient = useQueryClient()
    const [wired, setWired] = useState(false)

    useEffect(() => {
        if (isLoading) return
        setAuthHeaders(
            isAuthenticated
                ? async () => ({ Authorization: `Bearer ${await getAccessTokenSilently()}` })
                : async () => ({}),
        )
        setWired(true)
        void queryClient.invalidateQueries({ queryKey: ["me"] })
    }, [isLoading, isAuthenticated, getAccessTokenSilently, queryClient])

    if (!wired) {
        return (
            <Center h="100vh">
                <Loader />
            </Center>
        )
    }

    const controls: SessionControls = {
        type: "auth0",
        signIn: () =>
            void loginWithRedirect({ appState: { returnTo: window.location.pathname + window.location.search } }),
        signOut: () => {
            queryClient.clear()
            void logout({ logoutParams: { returnTo: `${window.location.origin}${config.basePath}/` } })
        },
        hint: { nickname: user?.nickname ?? "", name: user?.name ?? "" },
    }
    return <SessionProvider value={controls}>{children}</SessionProvider>
}
