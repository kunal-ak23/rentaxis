"use client";

import { useCallback, useEffect, useId, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { SelectPanel, SelectTrigger, handleListKey } from "@/components/ui/SelectPanel";

export type AsyncOption = { value: string; label: string; sublabel?: string };

type Props = {
    value: string;
    onChange: (value: string, option: AsyncOption | null) => void;
    /** Server search; called once with "" on open, then debounced ({@link DEBOUNCE_MS}) with the trimmed text. */
    search: (q: string) => Promise<AsyncOption[]>;
    /** Label for `value` when it is not among the current results. */
    selectedLabel?: string;
    placeholder?: string;
    searchPlaceholder?: string;
    className?: string;
    testId?: string;
    disabled?: boolean;
};

const DEBOUNCE_MS = 250;

type Status = "idle" | "loading" | "done" | "error";

/**
 * SearchableSelect whose options come from the server: the list is only ever
 * the first page of matches, so it scales to any portfolio size.
 *
 * Out-of-order responses are dropped: every request takes a sequence number
 * and only the latest one may write results. A failed search shows a retry
 * hint and leaves the value alone.
 */
export function AsyncSearchSelect({
    value,
    onChange,
    search,
    selectedLabel,
    placeholder,
    searchPlaceholder,
    className,
    testId,
    disabled,
}: Props) {
    const t = useTranslations("Pickers");
    const [open, setOpen] = useState(false);
    const [query, setQuery] = useState("");
    const [options, setOptions] = useState<AsyncOption[]>([]);
    const [status, setStatus] = useState<Status>("idle");
    const [highlightedIndex, setHighlightedIndex] = useState(-1);
    // The option last picked here, so the trigger keeps its label before the parent supplies one.
    const [picked, setPicked] = useState<AsyncOption | null>(null);
    const triggerRef = useRef<HTMLButtonElement>(null);
    const seqRef = useRef(0);
    const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const listboxId = useId();
    const placeholderText = placeholder ?? "— Select —";

    const clearTimer = () => {
        if (timerRef.current !== null) {
            clearTimeout(timerRef.current);
            timerRef.current = null;
        }
    };

    useEffect(() => clearTimer, []);

    const run = useCallback(
        (q: string) => {
            const seq = ++seqRef.current;
            setStatus("loading");
            search(q).then(
                (rows) => {
                    if (seq !== seqRef.current) return;
                    setOptions(rows);
                    setStatus("done");
                },
                () => {
                    if (seq !== seqRef.current) return;
                    setOptions([]);
                    setStatus("error");
                },
            );
        },
        [search],
    );

    const openPanel = () => {
        setOpen(true);
        run("");
    };

    const close = (returnFocus: boolean) => {
        clearTimer();
        seqRef.current++; // an in-flight response must not repopulate a closed panel
        setOpen(false);
        setQuery("");
        setHighlightedIndex(-1);
        setStatus("idle");
        if (returnFocus) triggerRef.current?.focus();
    };

    const onQueryChange = (q: string) => {
        setQuery(q);
        setHighlightedIndex(-1);
        clearTimer();
        timerRef.current = setTimeout(() => {
            timerRef.current = null;
            run(q.trim());
        }, DEBOUNCE_MS);
    };

    const selectIndex = (index: number) => {
        if (index === -1) {
            setPicked(null);
            onChange("", null);
        } else {
            const option = options[index];
            if (!option) return;
            setPicked(option);
            onChange(option.value, option);
        }
        close(true);
    };

    const known =
        options.find((o) => o.value === value) ?? (picked && picked.value === value ? picked : null);
    const selected = !value
        ? null
        : known ?? (selectedLabel ? { label: selectedLabel } : null);

    const message =
        status === "loading" && options.length === 0
            ? t("searching")
            : status === "error"
              ? t("searchFailed")
              : status === "done" && options.length === 0
                ? t("noMatches")
                : null;

    return (
        <div className="relative">
            <SelectTrigger
                ref={triggerRef}
                open={open}
                listboxId={listboxId}
                selected={selected}
                placeholder={placeholderText}
                onToggle={() => (open ? close(false) : openPanel())}
                className={className}
                testId={testId}
                disabled={disabled}
            />
            {open && (
                <SelectPanel
                    listboxId={listboxId}
                    query={query}
                    onQueryChange={onQueryChange}
                    onKeyDown={(e) =>
                        handleListKey(e, options.length, setHighlightedIndex, () => selectIndex(highlightedIndex), () => close(true))
                    }
                    searchPlaceholder={searchPlaceholder ?? t("typeToSearch")}
                    clearLabel={placeholderText}
                    rows={options}
                    selectedValue={value}
                    highlightedIndex={highlightedIndex}
                    onSelectIndex={selectIndex}
                    onDismiss={() => close(false)}
                    message={message}
                />
            )}
        </div>
    );
}
