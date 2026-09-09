import Link from "next/link";
import { getTranslations } from "next-intl/server";

/**
 * 404 boundary for everything under /[locale]. Without one, a bad URL rendered
 * Next's bare default page with no navigation back into the app and no Arabic.
 */
export default async function LocaleNotFound() {
    const t = await getTranslations("ErrorBoundary");

    return (
        <div className="flex min-h-[60vh] flex-col items-center justify-center gap-4 p-8 text-center">
            <h1 className="text-xl font-semibold text-gray-900">{t("notFoundTitle")}</h1>
            <p className="max-w-md text-sm text-gray-600">{t("notFoundDescription")}</p>
            <Link
                href="/dashboard"
                className="rounded-md bg-gray-900 px-4 py-2 text-sm font-medium text-white hover:bg-gray-700"
            >
                {t("goHome")}
            </Link>
        </div>
    );
}
