import type { ApiProblem } from "@/lib/api/types"
import { config } from "@/lib/config"
import type { ZodError } from "zod"

export type HttpMethod = "GET" | "POST" | "PUT" | "PATCH" | "DELETE"

export interface RequestOptions {
    method?: HttpMethod
    body?: unknown
    token?: string
}

export class ApiError extends Error {
    readonly problem: ApiProblem

    constructor(problem: ApiProblem) {
        super(problem.title)
        this.name = "ApiError"
        this.problem = problem
    }
}

export function problemOf(error: unknown): ApiProblem {
    if (error instanceof ApiError) return error.problem
    const title = error instanceof Error ? error.message : "Something went wrong"
    return { status: 0, title, issues: [] }
}

export function problemFromZod(error: ZodError, title: string): ApiProblem {
    return {
        status: 400,
        title,
        issues: error.issues.map((issue) => ({ path: pathOf(issue.path), message: issue.message })),
    }
}

function pathOf(segments: Array<string | number>): string {
    return segments.reduce<string>(
        (path, segment) =>
            typeof segment === "number" ? `${path}[${segment}]` : path === "" ? segment : `${path}.${segment}`,
        "",
    )
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
        throw new ApiError(await problemFrom(response))
    }
    if (response.status === 204) {
        return undefined as T
    }
    return (await response.json()) as T
}

async function problemFrom(response: Response): Promise<ApiProblem> {
    const fallback: ApiProblem = {
        status: response.status,
        title: `Request failed with status ${response.status}`,
        issues: [],
    }
    try {
        const payload = (await response.json()) as Partial<ApiProblem>
        if (typeof payload?.title !== "string") return fallback
        return {
            status: payload.status ?? response.status,
            title: payload.title,
            detail: payload.detail,
            issues: Array.isArray(payload.issues) ? payload.issues : [],
        }
    } catch {
        return fallback
    }
}
