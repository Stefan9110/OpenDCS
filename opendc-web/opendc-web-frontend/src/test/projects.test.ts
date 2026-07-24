import { formatUpdatedAt } from "@/lib/projects"
import dayjs from "dayjs"
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest"

describe("formatUpdatedAt", () => {
    beforeAll(() => {
        vi.useFakeTimers()
        vi.setSystemTime(new Date(2026, 6, 24, 12, 0, 0))
    })

    afterAll(() => {
        vi.useRealTimers()
    })

    it("labels today with the time", () => {
        expect(formatUpdatedAt(dayjs().hour(14).minute(30).toISOString())).toBe("Today, 14:30")
    })

    it("labels yesterday with the time", () => {
        const yesterday = dayjs().subtract(1, "day").hour(8).minute(15)
        expect(formatUpdatedAt(yesterday.toISOString())).toBe("Yesterday, 08:15")
    })

    it("keeps the full format for older dates", () => {
        const older = dayjs().subtract(10, "day").hour(9).minute(5)
        expect(formatUpdatedAt(older.toISOString())).toBe("Jul 14, 2026 09:05")
    })

    it("keeps the full format for future dates", () => {
        const future = dayjs().add(1, "day").hour(9).minute(5)
        expect(formatUpdatedAt(future.toISOString())).toBe("Jul 25, 2026 09:05")
    })
})
