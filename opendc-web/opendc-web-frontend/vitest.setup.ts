import "@testing-library/jest-dom/vitest"

const noop = () => {}

Object.defineProperty(window, "matchMedia", {
    writable: true,
    value: (query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addEventListener: noop,
        removeEventListener: noop,
        addListener: noop,
        removeListener: noop,
        dispatchEvent: () => false,
    }),
})

class ResizeObserverMock {
    observe = noop
    unobserve = noop
    disconnect = noop
}

window.ResizeObserver = ResizeObserverMock as unknown as typeof ResizeObserver
window.scrollTo = noop as typeof window.scrollTo

if (typeof globalThis.localStorage === "undefined") {
    const entries = new Map<string, string>()
    Object.defineProperty(globalThis, "localStorage", {
        configurable: true,
        value: {
            getItem: (key: string) => entries.get(key) ?? null,
            setItem: (key: string, value: string) => entries.set(key, String(value)),
            removeItem: (key: string) => entries.delete(key),
            clear: () => entries.clear(),
            key: (index: number) => [...entries.keys()][index] ?? null,
            get length() {
                return entries.size
            },
        },
    })
}
