export type NumericParam = { status: "ok"; value: number } | { status: "missing" } | { status: "invalid"; raw: string }

export interface ReadableParams {
    get(name: string): string | null
}

export function numericParam(params: ReadableParams, name: string): NumericParam {
    const raw = params.get(name)
    if (raw === null || raw.trim() === "") return { status: "missing" }
    if (!/^\d+$/.test(raw.trim())) return { status: "invalid", raw }
    return { status: "ok", value: Number(raw.trim()) }
}
