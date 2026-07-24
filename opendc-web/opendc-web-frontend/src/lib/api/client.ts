import { config } from "@/lib/config"

// Targets the current backend contract. The future SaaS API is specified in
// docs/superpowers/specs/2026-07-22-opendc-saas-refactor-design.md (WS1).

export type HttpMethod = "GET" | "POST" | "PUT" | "PATCH" | "DELETE"

export interface RequestOptions {
    method?: HttpMethod
    body?: unknown
    token?: string
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const { method = "GET", body, token } = options
    const headers: Record<string, string> = { "Content-Type": "application/json" }
    if (token) {
        headers.Authorization = `Bearer ${token}`
    }
    const response = await fetch(`${config.apiBaseUrl}/${path}`, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
    })
    if (!response.ok) {
        throw new Error(`Request to ${path} failed with status ${response.status}`)
    }
    return (await response.json()) as T
}
