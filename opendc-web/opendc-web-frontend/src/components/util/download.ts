export function fileSlug(name: string): string {
    return name.replaceAll(/\s+/g, "-").toLowerCase()
}

export function downloadText(fileName: string, text: string, type: string): void {
    const url = URL.createObjectURL(new Blob([text], { type }))
    const link = document.createElement("a")
    link.href = url
    link.download = fileName
    link.click()
    URL.revokeObjectURL(url)
}

export function downloadJson(name: string, value: unknown): void {
    downloadText(`${fileSlug(name)}.json`, JSON.stringify(value, null, 4), "application/json")
}
