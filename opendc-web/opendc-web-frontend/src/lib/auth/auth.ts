import type { Account } from "@/lib/api/types"
import { config } from "@/lib/config"
import { sampleAccount } from "@/lib/sample/account"

export interface AnonymousSession {
    status: "anonymous"
    userName: string
    avatarUrl: string
}

export interface AuthenticatedSession {
    status: "authenticated"
    userName: string
    avatarUrl: string
    email: string
    account: Account
}

export type AuthSession = AnonymousSession | AuthenticatedSession

const anonymousSession: AnonymousSession = {
    status: "anonymous",
    userName: "Anonymous",
    avatarUrl: "/img/avatar.svg",
}

export function useAuth(): AuthSession {
    if (config.authMode === "anonymous") return anonymousSession
    return {
        status: "authenticated",
        userName: "Ada Lovelace",
        avatarUrl: "/img/avatar.svg",
        email: "ada.lovelace@opendc.org",
        account: sampleAccount(),
    }
}
