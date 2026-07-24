import type { NextConfig } from "next"

const basePath = process.env.NEXT_BASE_PATH

const nextConfig: NextConfig = {
    output: "export",
    reactStrictMode: true,
    images: { unoptimized: true },
    basePath: basePath ? `/${basePath}` : undefined,
}

export default nextConfig
