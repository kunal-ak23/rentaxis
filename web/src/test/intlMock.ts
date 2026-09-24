import { vi } from "vitest";

/**
 * A `next-intl` module stand-in backed by the real English catalog:
 *
 *   vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
 *
 * Assertions then read the text a user sees, plurals and enum labels included,
 * and `t.has`/`t.raw` behave. One translator per namespace, stable across
 * renders like the real hook, so an effect that depends on `t` does not refire.
 */
export async function englishIntl() {
    const actual = await vi.importActual<typeof import("next-intl")>("next-intl");
    const messages = (await import("../../messages/en.json")).default;
    const cache = new Map<string, ReturnType<typeof actual.createTranslator>>();
    return {
        ...actual,
        useLocale: () => "en",
        useTranslations: (namespace?: string) => {
            const key = namespace ?? "";
            if (!cache.has(key)) {
                cache.set(key, actual.createTranslator({ locale: "en", messages, namespace: namespace as never }));
            }
            return cache.get(key)!;
        },
    };
}
