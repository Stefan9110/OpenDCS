import { sampleAccount } from "@/lib/account"
import type { Account } from "@/lib/api/types"
import { config } from "@/lib/config"

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

const sampleSession: AuthenticatedSession = {
    status: "authenticated",
    userName: "Ada Lovelace",
    avatarUrl: "/img/avatar.svg",
    email: "ada.lovelace@opendc.org",
    account: sampleAccount,
}

export function useAuth(): AuthSession {
    return config.authMode === "anonymous" ? anonymousSession : sampleSession
}
