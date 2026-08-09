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
  },
  experimental: {
    proxyTimeout: 300000, // 5 min — matches upload route maxDuration for large file uploads
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
      // NOTE: no direct /api/marketplace rewrite. Marketplace endpoints are
      // renter-authenticated and must go through /api/proxy/marketplace/** so
      // the middleware attaches X-User-* headers; a direct rewrite would skip
      // the middleware matcher and guarantee 401/403s.
      {
        source: '/public/l/:path*',
        destination: `${backendUrl}/public/l/:path*`,
      },
    ];
  },
};

export default withNextIntl(nextConfig);
