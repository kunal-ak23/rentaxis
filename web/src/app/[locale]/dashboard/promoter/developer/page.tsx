"use client";

import { useSyncExternalStore } from "react";
import { useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { Link } from "@/i18n/routing";
import { hasPermission, type UserRole } from "@/lib/rbac";

const API_PATH = "/api/v1/partner/promoter";
const PLACEHOLDER_ORIGIN = "https://<your Miftah host>";

const EXAMPLE_KEY = "mfk_live_EXAMPLEEXAMPLEEXAMPLEEXAMPLE";
const UNIT_A = "3f2b8c1e-5a47-4d9e-9b6a-0c1d2e3f4a51";
const UNIT_B = "7a9d4e20-1b3c-4f58-8e6d-5c4b3a291f02";
const PROP_A = "c1d2e3f4-a5b6-4789-90ab-cdef01234567";
const FINGERPRINT = "sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

const PING_JSON = JSON.stringify({
    promoterUserId: "0b7e5a10-2c4d-4e6f-8a9b-1c2d3e4f5a6b",
    tenantId: "5d4c3b2a-1f0e-4d9c-8b7a-6f5e4d3c2b1a",
}, null, 2);

const COUNT_JSON = JSON.stringify({
    properties: 2, units: 148, fingerprint: FINGERPRINT, generatedAt: "2026-10-29T08:15:00Z",
}, null, 2);

const UNITS_JSON = JSON.stringify({
    content: [
        { unitId: UNIT_A, unitNumber: "101", propertyId: PROP_A, propertyName: "Example Tower" },
        { unitId: UNIT_B, unitNumber: "102", propertyId: PROP_A, propertyName: "Example Tower" },
    ],
    page: 0, size: 500, totalElements: 148, totalPages: 1, fingerprint: FINGERPRINT,
}, null, 2);

const INVALID_JSON = '{"error":"invalid_api_key"}';
const RATE_JSON = '{"error":"rate_limited"}';

const noSubscribe = () => () => {};

function Code({ children }: { children: string }) {
    return (
        <pre dir="ltr" className="overflow-x-auto rounded-lg bg-gray-900 p-4 text-left text-xs text-gray-100">
            <code>{children}</code>
        </pre>
    );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
    return (
        <section className="space-y-3">
            <h2 className="text-lg font-semibold">{title}</h2>
            {children}
        </section>
    );
}

const P = ({ children }: { children: React.ReactNode }) => (
    <p className="text-sm leading-6 text-gray-700">{children}</p>
);

/**
 * Developer guide for the promoter units API (/dashboard/promoter/developer),
 * linked from the Developer tab. Prose is translated; code and JSON are not
 * and always render left-to-right. Same access check as the portal page.
 */
export default function PromoterDeveloperDocsPage() {
    const t = useTranslations("PromoterDocs");
    const tp = useTranslations("Promoter");
    const { data: session, status } = useSession();
    const canView = hasPermission(session?.user?.role as UserRole | undefined, "canUsePromoterPortal");

    // The origin is only known in the browser; read it after mount so server and client markup agree.
    const origin = useSyncExternalStore(noSubscribe, () => window.location.origin, () => PLACEHOLDER_ORIGIN);

    if (status === "loading") return null;
    if (!canView) return <p className="p-6 text-sm text-gray-500">{tp("noAccess")}</p>;

    const base = `${origin}${API_PATH}`;
    const curl = (path: string) => `curl -H "X-Api-Key: ${EXAMPLE_KEY}" \\\n  "${base}${path}"`;

    return (
        <div className="mx-auto max-w-3xl space-y-8 p-6">
            <div className="space-y-2">
                <Link href="/dashboard/promoter" className="text-sm underline">{t("back")}</Link>
                <h1 className="text-2xl font-semibold">{t("title")}</h1>
            </div>

            <Section title={t("overviewTitle")}>
                <P>{t("overviewP1")}</P>
                <P>{t("overviewP2")}</P>
            </Section>

            <Section title={t("keyTitle")}>
                <P>{t("keyP1")}</P>
                <P>{t("keyP2")}</P>
                <P>{t("keyP3")}</P>
            </Section>

            <Section title={t("baseUrlTitle")}>
                <P>{t("baseUrlP")}</P>
                <Code>{base}</Code>
            </Section>

            <Section title={t("authTitle")}>
                <P>{t("authP")}</P>
                <Code>{`X-Api-Key: ${EXAMPLE_KEY}`}</Code>
            </Section>

            <Section title={t("pingTitle")}>
                <P>{t("pingP")}</P>
                <P>{t("sampleRequest")}</P>
                <Code>{curl("/ping")}</Code>
                <P>{t("sampleResponse")}</P>
                <Code>{PING_JSON}</Code>
            </Section>

            <Section title={t("countTitle")}>
                <P>{t("countP")}</P>
                <P>{t("sampleRequest")}</P>
                <Code>{curl("/units/count")}</Code>
                <P>{t("sampleResponse")}</P>
                <Code>{COUNT_JSON}</Code>
            </Section>

            <Section title={t("unitsTitle")}>
                <P>{t("unitsP1")}</P>
                <P>{t("unitsP2")}</P>
                <P>{t("sampleRequest")}</P>
                <Code>{curl("/units?page=0&size=500")}</Code>
                <P>{t("sampleResponse")}</P>
                <Code>{UNITS_JSON}</Code>
            </Section>

            <Section title={t("syncTitle")}>
                <P>{t("syncIntro")}</P>
                <ol className="list-decimal space-y-1 ps-6 text-sm leading-6 text-gray-700">
                    {(["syncStep1", "syncStep2", "syncStep3", "syncStep4", "syncStep5"] as const).map(k => (
                        <li key={k}>{t(k)}</li>
                    ))}
                </ol>
            </Section>

            <Section title={t("validateTitle")}>
                <P>{t("validateP1")}</P>
                <P>{t("validateP2")}</P>
            </Section>

            <Section title={t("errorsTitle")}>
                <P>{t("errorsP1")}</P>
                <Code>{`HTTP/1.1 401\n${INVALID_JSON}`}</Code>
                <P>{t("errorsP2")}</P>
                <Code>{`HTTP/1.1 429\nRetry-After: 12\n${RATE_JSON}`}</Code>
            </Section>

            <Section title={t("securityTitle")}>
                <ul className="list-disc space-y-1 ps-6 text-sm leading-6 text-gray-700">
                    {(["security1", "security2", "security3"] as const).map(k => <li key={k}>{t(k)}</li>)}
                </ul>
            </Section>
        </div>
    );
}
