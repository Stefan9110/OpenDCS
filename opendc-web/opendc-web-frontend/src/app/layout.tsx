import "@mantine/core/styles.css"
import "@mantine/notifications/styles.css"
import "@mantine/charts/styles.css"

import { ColorSchemeScript, mantineHtmlProps } from "@mantine/core"
import type { Metadata, Viewport } from "next"
import { Geist_Mono, Instrument_Sans } from "next/font/google"
import type { ReactNode } from "react"
import { Providers } from "./providers"

const openDcFont = Instrument_Sans({
    subsets: ["latin"],
    variable: "--font-opendc",
})

const openDcFontMono = Geist_Mono({
    subsets: ["latin"],
    variable: "--font-opendc-mono",
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
        <html lang="en" className={`${openDcFont.variable} ${openDcFontMono.variable}`} {...mantineHtmlProps}>
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
