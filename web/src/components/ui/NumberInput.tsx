"use client";

import { useEffect, useId, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { moneyInputError, parseMoneyInput, type MoneyInputError, type MoneyInputOptions } from "@/lib/money";

type Props = Omit<React.InputHTMLAttributes<HTMLInputElement>, "value" | "onChange" | "type"> & {
    value: number;
    /**
     * The parsed number. In money mode `meta.invalid` says the text was refused
     * (the value is then 0 — no amount); a caller whose own state would remount
     * the field on a 0 (an "auto" cell) can ignore that report and keep its state.
     */
    onChange: (value: number, meta?: { invalid: boolean }) => void;
    /**
     * Render a zero as "0" rather than as an empty field. For a stored setting
     * — a grace period, a fine amount — zero is a configured value, and a blank
     * box reads as "not set up yet". Leave it off for the amount fields the
     * user is filling in, which is where the empty start is the point.
     */
    showZero?: boolean;
    /**
     * Break-it round 1 (money) F1: this field is an amount of money. It becomes a
     * text field (`inputMode="decimal"`) read by `parseMoneyInput` — at most two
     * decimals, a comma only as thousands grouping, Arabic-Indic digits read as
     * digits, no more than the ledger holds, and at least 0.01 unless the options
     * allow zero. A refused entry keeps its text, shows why underneath, is marked
     * `aria-invalid` / `data-money-invalid` (which `LeaseDialog` refuses to
     * submit), and reports **0** — no amount — so a form's own "> 0" gate stays
     * shut. It is never rounded to something the user did not type.
     */
    money?: true | MoneyInputOptions;
};

/**
 * A number input that starts empty instead of showing a literal 0.
 *
 * Binding a numeric field straight to state renders "0" in every untouched
 * amount box, and typing into one appends to that zero — you get 05000 unless
 * you remember to clear it first. Reported by a client as annoying across the
 * whole app, which it was: most of these forms open on zeros.
 *
 * Writing `value={amount || ""}` fixes the display but throws away a 0 the
 * user typed on purpose — the field blanks itself mid-edit, and fields where
 * zero is a real answer (a grace period, a waived fine) become impossible to
 * set. So this keeps the typed text as its own state and reports the parsed
 * number upward. An empty box reports 0, which is what the old inputs did for
 * an empty box anyway.
 */
export function NumberInput(props: Props) {
    // Two components, not one with a branch, so each keeps a fixed set of hooks and
    // a plain number field renders without an intl provider.
    return props.money === undefined ? <PlainNumberInput {...props} /> : <MoneyNumberInput {...props} />;
}

function PlainNumberInput({ value, onChange, showZero = false, ...props }: Props) {
    // `money` is undefined on this path; keep it off the <input>.
    const { money: _unused, ...rest } = props;
    void _unused;
    const asText = (n: number) => (n === 0 && !showZero ? "" : String(n));
    const [text, setText] = useState(() => asText(value));
    const lastReported = useRef(value);

    // Re-sync only when the value changes somewhere other than this input —
    // a reset, a prefill, a recalculation. Without the guard, typing "5.10"
    // would be rewritten to "5.1" under the cursor.
    useEffect(() => {
        if (value === lastReported.current) return;
        lastReported.current = value;
        setText(asText(value));
        // eslint-disable-next-line react-hooks/exhaustive-deps -- asText is derived from showZero, which does not change for a mounted field
    }, [value]);

    return (
        <input
            {...rest}
            type="number"
            value={text}
            onChange={(e) => {
                const next = e.target.value;
                setText(next);
                const parsed = next === "" ? 0 : Number(next);
                if (Number.isNaN(parsed)) return;
                lastReported.current = parsed;
                onChange(parsed);
            }}
        />
    );
}

function MoneyNumberInput({ value, onChange, showZero = false, money, onBlur, ...rest }: Props) {
    const t = useTranslations("MoneyInput");
    const errorId = useId();
    const asText = (n: number) => (n === 0 && !showZero ? "" : String(n));
    const [text, setText] = useState(() => asText(value));
    const [moneyError, setMoneyError] = useState<MoneyInputError | null>(null);
    // Announced to assistive tech on blur (or when a submit focuses the field — its
    // aria-describedby is read then), not on every intermediate keystroke.
    const [announce, setAnnounce] = useState(false);
    const lastReported = useRef(value);
    const inputRef = useRef<HTMLInputElement>(null);
    const moneyOptions: MoneyInputOptions = money === true || money === undefined ? {} : money;

    useEffect(() => {
        if (value === lastReported.current) return;
        lastReported.current = value;
        setText(asText(value));
        setMoneyError(null);
        // The value reset outside this field's own onChange (a cancel, a reload, a
        // save that reverts to the saved amount) — clear the stale native message
        // along with the visible one, or a real <form> keeps refusing to submit a
        // field that looks clean.
        inputRef.current?.setCustomValidity("");
        // eslint-disable-next-line react-hooks/exhaustive-deps -- asText is derived from showZero, which does not change for a mounted field
    }, [value]);

    const invalid = moneyError !== null;
    const describedBy = [rest["aria-describedby"], invalid ? errorId : null].filter(Boolean).join(" ") || undefined;
    return (
        <>
            <input
                {...rest}
                ref={inputRef}
                type="text"
                inputMode="decimal"
                dir={rest.dir ?? "ltr"}
                value={text}
                aria-invalid={invalid}
                aria-describedby={describedBy}
                data-money-invalid={invalid ? "true" : undefined}
                onBlur={(e) => {
                    if (invalid) setAnnounce(true);
                    onBlur?.(e);
                }}
                onChange={(e) => {
                    const next = e.target.value;
                    setText(next);
                    setAnnounce(false);
                    const parsed = parseMoneyInput(next, moneyOptions);
                    const reportedValue = parsed.ok ? parsed.value ?? 0 : 0;
                    setMoneyError(parsed.ok ? null : parsed.error);
                    e.target.setCustomValidity(parsed.ok ? "" : t(parsed.error));
                    lastReported.current = reportedValue;
                    onChange(reportedValue, { invalid: !parsed.ok });
                }}
            />
            {moneyError && <MoneyFieldError id={errorId} error={moneyError} announce={announce} />}
        </>
    );
}

/**
 * Batch 4 review #5: the money field for a form that keeps the typed text in its own
 * state (a bank-rec balance, a split, a penalty's new amount). Same parse, sentence,
 * `aria-describedby`, blur-time announcement and `data-money-invalid` as NumberInput's
 * money mode; the native validity message is set too, so a real `<form>` refuses to
 * submit it. The caller reads the value with `moneyValueOrNull` and gates on
 * {@link moneyTextInvalid}.
 */
export function MoneyTextInput({ value, onChange, options, onBlur, ...rest }:
    Omit<React.InputHTMLAttributes<HTMLInputElement>, "value" | "onChange" | "type"> & {
        value: string;
        onChange: (text: string) => void;
        options?: MoneyInputOptions;
    }) {
    const t = useTranslations("MoneyInput");
    const errorId = useId();
    const [announce, setAnnounce] = useState(false);
    const error = value.trim() === "" ? null : moneyInputError(value, options);
    const ref = useRef<HTMLInputElement>(null);
    useEffect(() => {
        ref.current?.setCustomValidity(error ? t(error) : "");
    }, [error, t]);
    const describedBy = [rest["aria-describedby"], error ? errorId : null].filter(Boolean).join(" ") || undefined;
    return (
        <>
            <input
                {...rest}
                ref={ref}
                type="text"
                inputMode="decimal"
                dir={rest.dir ?? "ltr"}
                value={value}
                aria-invalid={error !== null}
                aria-describedby={describedBy}
                data-money-invalid={error ? "true" : undefined}
                onBlur={(e) => {
                    if (error) setAnnounce(true);
                    onBlur?.(e);
                }}
                onChange={(e) => {
                    setAnnounce(false);
                    onChange(e.target.value);
                }}
            />
            {error && <MoneyFieldError id={errorId} error={error} announce={announce} />}
        </>
    );
}

/** True when a typed amount is present and refused (blank is not invalid — the form decides if it is required). */
export function moneyTextInvalid(text: string, options?: MoneyInputOptions): boolean {
    return text.trim() !== "" && moneyInputError(text, options) !== null;
}

/**
 * Break-it round 1 (money) F1: the submit-side half of money mode. A money field
 * that refused its text reports 0 and marks itself `data-money-invalid`; a form
 * that is not a `<form>` (a dialog, a wizard step, a grid's save button) calls
 * this before sending. It focuses the first refused field under `root` and says
 * whether there was one — true means "do not submit".
 */
export function focusFirstInvalidMoney(root: ParentNode | null | undefined): boolean {
    const invalid = root?.querySelector<HTMLElement>('[data-money-invalid="true"]');
    if (!invalid) return false;
    invalid.focus();
    return true;
}

/**
 * The sentence under a refused amount. Its own component so the translation
 * hook runs only when there is something to say. Not a live region while the
 * user is still typing — link it with `aria-describedby` (via `id`) and pass
 * `announce` once the field is left or a submit is refused.
 */
export function MoneyFieldError({ error, className, id, announce = false }: {
    error: MoneyInputError;
    className?: string;
    id?: string;
    announce?: boolean;
}) {
    const t = useTranslations("MoneyInput");
    return (
        <p
            id={id}
            role={announce ? "alert" : undefined}
            data-testid="money-input-error"
            className={className ?? "mt-1 text-[11px] text-error"}
        >
            {t(error)}
        </p>
    );
}
