"use client";

import { useEffect, useRef, useState } from "react";

type Props = Omit<React.InputHTMLAttributes<HTMLInputElement>, "value" | "onChange" | "type"> & {
    value: number;
    onChange: (value: number) => void;
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
export function NumberInput({ value, onChange, ...rest }: Props) {
    const [text, setText] = useState(() => (value === 0 ? "" : String(value)));
    const lastReported = useRef(value);

    // Re-sync only when the value changes somewhere other than this input —
    // a reset, a prefill, a recalculation. Without the guard, typing "5.10"
    // would be rewritten to "5.1" under the cursor.
    useEffect(() => {
        if (value === lastReported.current) return;
        lastReported.current = value;
        setText(value === 0 ? "" : String(value));
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
