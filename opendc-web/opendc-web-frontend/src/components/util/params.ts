import type { Id } from "@/lib/api/types"

export type IdParam = { status: "ok"; value: Id } | { status: "missing" }

export interface ReadableParams {
    get(name: string): string | null
}

/**
 * Reads an entity id from the query string. Identifiers are opaque, so the only thing the client
 * can tell is whether one was supplied at all: judging its shape here would just be a second,
 * weaker copy of the check the server already makes when it looks the id up.
 */
export function idParam(params: ReadableParams, name: string): IdParam {
    const raw = params.get(name)?.trim()
    if (raw === undefined || raw === "") return { status: "missing" }
    return { status: "ok", value: raw }
}
