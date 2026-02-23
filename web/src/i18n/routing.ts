import { defineRouting } from 'next-intl/routing';
import createMiddleware from 'next-intl/middleware';

export const routing = defineRouting({
    locales: ['en', 'ar'],
    defaultLocale: 'en'
});

export default createMiddleware(routing);

export const config = {
    matcher: ['/', '/(ar|en)/:path*']
};
