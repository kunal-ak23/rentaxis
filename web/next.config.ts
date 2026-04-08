import type { NextConfig } from "next";
import createNextIntlPlugin from "next-intl/plugin";
import { execSync } from "child_process";

const withNextIntl = createNextIntlPlugin('./src/i18n/request.ts');

const backendUrl = process.env.BACKEND_URL || 'http://localhost:8080';

let gitHash = 'dev';
try {
  gitHash = execSync('git rev-parse --short=7 HEAD').toString().trim();
} catch {}

const nextConfig: NextConfig = {
  output: "standalone",
  env: {
    NEXT_PUBLIC_APP_VERSION: `0.6.0.${gitHash}`,
    NEXT_PUBLIC_BACKEND_URL: backendUrl,
  },
  experimental: {
    proxyTimeout: 120000,
  },
  async rewrites() {
    return [
      {
        source: '/api/proxy/:path*',
        destination: `${backendUrl}/api/:path*`,
      },
      {
        source: '/api/v1/assets/serve/:path*',
        destination: `${backendUrl}/api/v1/assets/serve/:path*`,
      },
      {
        source: '/api/marketplace/:path*',
        destination: `${backendUrl}/api/marketplace/:path*`,
      },
    ];
  },
};

export default withNextIntl(nextConfig);
