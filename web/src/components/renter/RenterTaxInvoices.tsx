"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { vatApi, type TaxInvoice } from "@/lib/api/leasing";
import { TaxInvoiceList } from "@/components/leases/VatScheduleTab";

/**
 * The renter's tax invoices (spec 2026-09-24 §1: every instalment's VAT tax point
 * issues one). `GET /tax-invoices/mine` answers only the caller's own, and each PDF
 * link is checked again on the server against the invoice's addressee, so nothing
 * here decides whose documents are shown.
 *
 * Silent when there are none — a residential tenancy never has any — and when the
 * list cannot be loaded: the payments on this screen matter more than a document
 * list beside them.
 */
export default function RenterTaxInvoices() {
    const t = useTranslations("VatSchedule");
    const [invoices, setInvoices] = useState<TaxInvoice[] | null>(null);

    useEffect(() => {
        let live = true;
        vatApi
            .myInvoices()
            .then(list => {
                if (live) setInvoices(list);
            })
            .catch(() => {
                if (live) setInvoices([]);
            });
        return () => {
            live = false;
        };
    }, []);

    if (!invoices || invoices.length === 0) return null;
    return (
        <div className="mt-10" data-testid="renter-tax-invoices">
            <TaxInvoiceList invoices={invoices} title={t("myInvoicesTitle")} />
        </div>
    );
}
