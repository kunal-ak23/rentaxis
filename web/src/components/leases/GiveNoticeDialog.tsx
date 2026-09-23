"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import LeaseDialog from "./LeaseDialog";
import { businessTodayIso } from "@/lib/businessDate";
import type { GiveNoticeInput, NoticeParty } from "@/lib/api/leasing";

/**
 * "Give notice" (#27): ACTIVE → NOTICE_GIVEN, now with the particulars a
 * notice is judged by — the day it was given (default today, Asia/Dubai; never
 * in the future), who gave it (a renter's notice and a landlord's are different
 * instruments in the UAE) and the move-out date it names, if any. Still writes
 * no journal.
 */

const field = "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1";

type Props = {
    open: boolean;
    busy: boolean;
    onClose: () => void;
    onConfirm: (input: GiveNoticeInput) => void;
    /**
     * The server's refusal, shown inside the dialog. A failed notice keeps the
     * dialog open with what was typed, instead of closing it and making the user
     * enter the date, party, move-out and notes again (web review M8).
     */
    error?: string | null;
};

export default function GiveNoticeDialog({ open, busy, onClose, onConfirm, error }: Props) {
    const t = useTranslations("Leasing");
    const [noticeDate, setNoticeDate] = useState(businessTodayIso());
    const [givenBy, setGivenBy] = useState<NoticeParty>("RENTER");
    const [moveOut, setMoveOut] = useState("");
    const [notes, setNotes] = useState("");

    useEffect(() => {
        if (open) {
            setNoticeDate(businessTodayIso());
            setGivenBy("RENTER");
            setMoveOut("");
            setNotes("");
        }
    }, [open]);

    const today = businessTodayIso();
    const invalid = !noticeDate || noticeDate > today || (!!moveOut && moveOut < noticeDate);

    return (
        <LeaseDialog
            open={open}
            title={t("giveNotice")}
            onClose={onClose}
            onConfirm={() => onConfirm({
                noticeDate,
                givenBy,
                intendedMoveOutDate: moveOut || null,
                notes: notes.trim() || null,
            })}
            confirmText={t("giveNotice")}
            cancelText={t("cancel")}
            confirmDisabled={invalid}
            busy={busy}
            confirmTestId="lease-give-notice-confirm"
        >
            <p className="text-[11px] text-muted mb-4">{t("giveNoticeConfirm")}</p>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                    <label className={label} htmlFor="notice-given-by">{t("noticeGivenBy")}</label>
                    <select id="notice-given-by" className={field} value={givenBy} onChange={e => setGivenBy(e.target.value as NoticeParty)}>
                        <option value="RENTER">{t("noticeParty.RENTER")}</option>
                        <option value="LANDLORD">{t("noticeParty.LANDLORD")}</option>
                    </select>
                </div>
                <div>
                    <label className={label} htmlFor="notice-date">{t("noticeDate")}</label>
                    <input id="notice-date" type="date" max={today} className={field} value={noticeDate} onChange={e => setNoticeDate(e.target.value)} />
                </div>
                <div>
                    <label className={label} htmlFor="notice-move-out">{t("intendedMoveOut")}</label>
                    <input id="notice-move-out" type="date" min={noticeDate || undefined} className={field} value={moveOut} onChange={e => setMoveOut(e.target.value)} />
                </div>
                <div className="sm:col-span-2">
                    <label className={label} htmlFor="notice-notes">{t("noticeNotes")}</label>
                    <input id="notice-notes" className={field} value={notes} onChange={e => setNotes(e.target.value)} />
                </div>
            </div>
            {givenBy === "LANDLORD" && (
                <p className="mt-3 text-[11px] text-warning">{t("landlordNoticeHint")}</p>
            )}
            {error && <p role="alert" data-testid="give-notice-error" className="mt-3 text-[11px] text-error bg-error/10 rounded-lg px-3 py-2">{error}</p>}
        </LeaseDialog>
    );
}
