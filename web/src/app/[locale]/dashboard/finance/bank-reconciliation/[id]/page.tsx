"use client";

import { useParams } from "next/navigation";
import { BankReconciliationWorkspace } from "@/components/finance/bankrec/BankReconciliationWorkspace";

/** The matching workspace for one bank account (finance-ops spec §3). */
export default function BankReconciliationWorkspacePage() {
    const params = useParams<{ id: string }>();
    return <BankReconciliationWorkspace bankAccountId={params.id} />;
}
