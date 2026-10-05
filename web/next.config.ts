import type { NextConfig } from "next";
import createNextIntlPlugin from "next-intl/plugin";
import { execSync } from "child_process";

const withNextIntl = createNextIntlPlugin('./src/i18n/request.ts');

const backendUrl = process.env.BACKEND_URL || 'http://localhost:8080';

// CI passes the full 40-char SHA; the sidebar shows the first 8 (v0.6.0.31c3d081).
let gitHash = (process.env.APP_GIT_SHA || 'dev').slice(0, 8);
if (!process.env.APP_GIT_SHA) {
  try {
    gitHash = execSync('git rev-parse --short=8 HEAD').toString().trim();
  } catch {}
}

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
      // Bug 26/27: a live listing's photos, for anyone (Caddy sends /api/v1/* to
      // the backend in production; this covers a stack without Caddy).
      {
        source: '/api/v1/public/listing-media/:path*',
        destination: `${backendUrl}/api/v1/public/listing-media/:path*`,
      },
      {
        source: '/public/l/:path*',
        destination: `${backendUrl}/public/l/:path*`,
      },
    ];
  },
};

export default withNextIntl(nextConfig);
