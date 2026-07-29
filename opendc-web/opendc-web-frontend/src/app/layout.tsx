import "@mantine/core/styles.css"
import "@mantine/notifications/styles.css"
import "@mantine/charts/styles.css"

import { ColorSchemeScript, mantineHtmlProps } from "@mantine/core"
import type { Metadata, Viewport } from "next"
import { Inter } from "next/font/google"
import type { ReactNode } from "react"
import { Providers } from "./providers"

const interFont = Inter({
    subsets: ["latin"],
    variable: "--font-inter",
})

export const metadata: Metadata = {
    title: "OpenDC",
    description: "Collaborative Datacenter Simulation and Exploration for Everybody",
    icons: { icon: "/favicon.ico" },
    manifest: "/manifest.json",
}

export const viewport: Viewport = {
    themeColor: "#00A6D6",
}

export default function RootLayout({ children }: { children: ReactNode }) {
    return (
        <html lang="en" className={interFont.variable} {...mantineHtmlProps}>
            <head>
                <title>OpenDC</title>
                <ColorSchemeScript defaultColorScheme="auto" />
            </head>
            <body>
                <Providers>{children}</Providers>
            </body>
        </html>
    )
}
