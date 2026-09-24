/**
 * Latin words left in rendered text, for "no English UI chrome under Arabic"
 * checks.
 *
 * `data` lists the values the fixture puts on screen (names, references, unit
 * numbers, emails): those are data, not chrome, and are removed before the
 * scan. Whatever Latin word remains came from the page itself: a label that
 * was never translated, an enum code shown raw, or a key path rendered because
 * the catalog lacks it.
 */
export function leftoverLatinWords(text: string, data: readonly string[] = []): string[] {
    let rest = text;
    for (const value of [...data].sort((a, b) => b.length - a.length)) {
        if (value) rest = rest.split(value).join(" ");
    }
    return [...new Set(rest.match(/[A-Za-z]{2,}/g) ?? [])];
}

/** All visible text of a container, including form control placeholders and option labels. */
export function visibleText(container: HTMLElement): string {
    const placeholders = [...container.querySelectorAll<HTMLInputElement>("[placeholder]")]
        .map((el) => el.getAttribute("placeholder") ?? "");
    const titles = [...container.querySelectorAll<HTMLElement>("[title],[aria-label]")]
        .map((el) => `${el.getAttribute("title") ?? ""} ${el.getAttribute("aria-label") ?? ""}`);
    return [container.textContent ?? "", ...placeholders, ...titles].join(" ");
}
