"use client"

import { Auth0Bridge } from "@/components/layout/Auth0Bridge"
import type { AuthSettings } from "@/lib/api/types"
import { config } from "@/lib/config"
import { Auth0Provider } from "@auth0/auth0-react"
import { useRouter } from "next/navigation"
import type { ReactNode } from "react"

type Auth0Settings = Extract<AuthSettings, { type: "auth0" }>

/**
 * Signs in through the Auth0 tenant the server named. Tokens are kept in local storage and renewed
 * with refresh tokens, since browsers block the third-party cookies a silent renewal would need,
 * and a session held only in memory would end at every reload.
 */
export function Auth0Session({ settings, children }: Readonly<{ settings: Auth0Settings; children: ReactNode }>) {
    const router = useRouter()

    return (
        <Auth0Provider
            domain={settings.domain}
            clientId={settings.clientId}
            authorizationParams={{
                audience: settings.audience,
                redirect_uri: `${window.location.origin}${config.basePath}/`,
            }}
            cacheLocation="localstorage"
            useRefreshTokens
            onRedirectCallback={(state) => router.replace(typeof state?.returnTo === "string" ? state.returnTo : "/")}
        >
            <Auth0Bridge>{children}</Auth0Bridge>
        </Auth0Provider>
    )
}
