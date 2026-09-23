"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { Plus } from "lucide-react";
import { hasPermission, type UserRole } from "@/lib/rbac";
import PenaltyQueue from "@/components/penalties/PenaltyQueue";
import RaisePenaltyDialog from "@/components/penalties/RaisePenaltyDialog";

/**
 * The fines raised against one contract.
 *
 * Proposing and deciding are deliberately different permissions, mirroring
 * PenaltyAssessmentController: spotting that a renter should be fined is part
 * of running a building, so a property manager may propose; turning that
 * proposal into a charge on the ledger writes a journal, so only finance may
 * approve, waive or reverse it. The decision table itself is `PenaltyQueue`,
 * scoped to this lease — this tab used to carry a second copy of that table
 * and its own approve/waive/reverse wiring, which is how it came to offer
 * Waive without the shared queue's date/note dialogs.
 */

type Props = { leaseId: string; userRole: UserRole | undefined };

export default function LeasePenaltiesTab({ leaseId, userRole }: Props) {
    const t = useTranslations("Cheques");
    const tl = useTranslations("Leasing");

    const canPropose = hasPermission(userRole, "canProposePenalties");

    const [reloadKey, setReloadKey] = useState(0);

    const [proposeOpen, setProposeOpen] = useState(false);
    return (
        <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm" data-testid="lease-penalties-tab">
            <div className="px-4 py-3 border-b border-border flex items-center justify-between gap-3">
                <h3 className="text-xs font-semibold text-muted uppercase tracking-wider">{t("penalties")}</h3>
                {canPropose && (
                    <button
                        type="button"
                        data-testid="penalty-propose-open"
                        onClick={() => setProposeOpen(true)}
                        className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-semibold border border-border text-foreground hover:bg-input/40 cursor-pointer"
                    >
                        <Plus size={12} /> {tl("raisePenalty")}
                    </button>
                )}
            </div>

            <RaisePenaltyDialog
                open={proposeOpen && canPropose}
                leaseId={leaseId}
                onClose={() => setProposeOpen(false)}
                onRaised={() => {
                    setProposeOpen(false);
                    // PenaltyQueue owns its own fetch; bump its key so the freshly
                    // proposed row shows up on the PROPOSED tab without a page reload.
                    setReloadKey(k => k + 1);
                }}
            />

            <div className="p-4">
                <PenaltyQueue key={reloadKey} userRole={userRole} leaseId={leaseId} />
            </div>
        </div>
    );
}
