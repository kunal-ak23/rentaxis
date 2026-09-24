"use client";

import { Fragment, useCallback, useEffect, useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import { Ban, Download, Printer, Send, ShieldCheck, Trash2 } from "lucide-react";
import { Link, useRouter } from "@/i18n/routing";
import { PaymentRunWizard, RunStatusChip } from "@/components/finance/PaymentRunWizard";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount } from "@/lib/api/ledger";
import { groupByVendor, paymentRunsApi, type PaymentRun } from "@/lib/api/payables";
import { hasPermission, type UserRole } from "@/lib/rbac";

const th = "px-3 py-2.5 text-[10px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap text-start";
const td = "px-3 py-2 text-xs";
const button = "flex items-center gap-1.5 px-3 py-2 rounded-lg bg-surface text-foreground border border-border text-xs font-bold hover:bg-input transition-all cursor-pointer disabled:opacity-50";

/**
 * One payment run. A DRAFT opens in the wizard (edit, preview, post) and can be
 * cancelled or deleted. A POSTED run lists each vendor's voucher, the bank
 * upload file (transfers) and the cheque list (cheques). Undoing one vendor's
 * payment is an amend or reverse of its voucher; the run stays POSTED.
 */
export default function PaymentRunPage() {
    const t = useTranslations("PaymentRuns");
    const tCommon = useTranslations("Common");
    const { id } = useParams<{ id: string }>();
    const router = useRouter();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canManagePayables");
    const [run, setRun] = useState<PaymentRun | null>(null);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [confirm, setConfirm] = useState<"cancel" | "delete" | null>(null);
    const [busy, setBusy] = useState(false);

    const load = useCallback(async () => {
        setLoadError(null);
        try {
            setRun(await paymentRunsApi.get(id));
        } catch (err) {
            setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
        }
    }, [id, tCommon]);

    useEffect(() => {
        if (allowed) load();
    }, [allowed, load]);

    const byVendor = useMemo(() => [...groupByVendor(run?.items ?? []).values()], [run]);

    if (userRole && !allowed) {
        return (
            <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center max-w-4xl">
                <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                <p className="text-sm text-muted">{t("accessDenied")}</p>
            </div>
        );
    }

    const act = async () => {
        if (!run || !confirm) return;
        setBusy(true);
        try {
            if (confirm === "delete") {
                await paymentRunsApi.remove(run.id);
                router.push("/dashboard/finance/payables/payment-runs");
            } else {
                setRun(await paymentRunsApi.cancel(run.id));
            }
            setConfirm(null);
        } catch (err) {
            setLoadError(err instanceof ApiError ? err.message : t("actionFailed"));
            setConfirm(null);
        } finally {
            setBusy(false);
        }
    };

    return (
        <div>
            <div className="flex items-start justify-between gap-4 mb-6 print:hidden">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <Send size={20} className="text-primary rtl:-scale-x-100" />
                        <bdi dir="ltr">{run?.runNumber ?? ""}</bdi>
                        {run && <RunStatusChip status={run.status} />}
                    </h1>
                    {run && (
                        <p className="text-xs text-muted font-medium">
                            <bdi dir="ltr">{run.paymentDate}</bdi> · {t(`methods.${run.method}`)} · {run.paymentAccountName}
                            {run.chequeDate && <> · {t("chequeDate")} <bdi dir="ltr">{run.chequeDate}</bdi></>}
                        </p>
                    )}
                </div>
                {run?.status === "DRAFT" && (
                    <div className="flex gap-2">
                        <button type="button" className={button} onClick={() => setConfirm("cancel")} data-testid="run-cancel">
                            <Ban size={13} />{t("cancelRun")}
                        </button>
                        <button type="button" className={button} onClick={() => setConfirm("delete")} data-testid="run-delete">
                            <Trash2 size={13} />{t("deleteRun")}
                        </button>
                    </div>
                )}
                {run?.status === "POSTED" && (
                    <div className="flex gap-2">
                        {run.method === "TRANSFER" && (
                            <a className={button} href={paymentRunsApi.bankFileUrl(run.id)} data-testid="run-bank-file">
                                <Download size={13} />{t("bankFile")}
                            </a>
                        )}
                        {run.method === "CHEQUE" && (
                            <button type="button" className={button} onClick={() => window.print()} data-testid="run-print-cheques">
                                <Printer size={13} />{t("printChequeList")}
                            </button>
                        )}
                    </div>
                )}
            </div>

            {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}

            {run?.status === "DRAFT" && <PaymentRunWizard key={run.id} run={run} />}

            {run && run.status !== "DRAFT" && (
                <>
                    {run.status === "POSTED" && <p className="text-xs text-muted mb-3 print:hidden">{t("afterPostNote")}</p>}
                    <div className="bg-surface rounded-xl border border-border overflow-x-auto">
                        <table className="w-full" data-testid="run-items">
                            <thead className="bg-background border-b border-border">
                                <tr>
                                    <th className={th}>{t("vendor")}</th>
                                    <th className={th}>{t("invoice")}</th>
                                    <th className={th}>{t("dueDate")}</th>
                                    <th className={`${th} text-end`}>{t("amount")}</th>
                                    <th className={th}>{t("voucher")}</th>
                                    {run.method === "CHEQUE" && <th className={th}>{t("chequeNo")}</th>}
                                    <th className={`${th} text-end`}>{t("netPayment")}</th>
                                </tr>
                            </thead>
                            <tbody>
                                {byVendor.map(rows => (
                                    <Fragment key={rows[0].vendorId}>
                                        {rows.map((i, n) => (
                                            <tr key={i.id} className="border-b border-border">
                                                <td className={`${td} font-semibold`}>{n === 0 ? i.vendorName : ""}</td>
                                                <td className={td}>{i.invoiceNumber ?? i.docNumber}</td>
                                                <td className={td}><bdi dir="ltr">{i.dueDate}</bdi></td>
                                                <td className={`${td} text-end`}><bdi dir="ltr" className="tabular-nums">{fmtAmount(i.amount)}</bdi></td>
                                                <td className={td}>
                                                    {n === 0 && (i.bpvId ? (
                                                        <span className="flex items-center gap-2">
                                                            <Link href={`/dashboard/finance/vouchers/payment?id=${i.bpvId}`} className="text-primary hover:underline">
                                                                <bdi dir="ltr">{i.bpvNumber}</bdi>
                                                            </Link>
                                                            {i.bpvStatus === "REVERSED" && (
                                                                <span className="text-[10px] font-bold text-danger" data-testid={`run-reversed-${i.bpvNumber}`}>{t("reversed")}</span>
                                                            )}
                                                        </span>
                                                    ) : <span className="text-muted">{t("advanceOnly")}</span>)}
                                                </td>
                                                {run.method === "CHEQUE" && <td className={td}>{n === 0 && <bdi dir="ltr">{i.chequeNumber}</bdi>}</td>}
                                                <td className={`${td} text-end`}>
                                                    {n === 0 && i.bpvAmount != null && <bdi dir="ltr" className="tabular-nums font-bold">{fmtAmount(i.bpvAmount)}</bdi>}
                                                </td>
                                            </tr>
                                        ))}
                                    </Fragment>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </>
            )}

            <ConfirmDialog
                isOpen={confirm !== null}
                onClose={() => setConfirm(null)}
                onConfirm={act}
                title={confirm === "delete" ? t("deleteRun") : t("cancelRun")}
                description={confirm === "delete" ? t("confirmDelete") : t("confirmCancel")}
                confirmText={confirm === "delete" ? t("deleteRun") : t("cancelRun")}
                cancelText={t("close")}
                isDestructive
                isLoading={busy}
                confirmTestId="run-confirm-action"
            />
        </div>
    );
}
