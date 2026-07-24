import { notifications } from "@mantine/notifications"

export function notifyComingSoon(feature: string): void {
    notifications.show({
        title: `${feature} is not ready yet`,
        message: "This is a preview build and is not wired up to the backend yet.",
        color: "opendc",
    })
}
