"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Mail, MessageCircle, Phone } from "lucide-react";
import LeaseDialog from "@/components/leases/LeaseDialog";
import LogInteractionDialog from "@/components/leases/LogInteractionDialog";
import type { LeaseDetail } from "@/lib/api/leasing";

type RenterContact = { nameEn: string | null; nameAr?: string | null; phone: string | null; email: string | null };

/** wa.me wants the number in international form, digits only. */
export function whatsappHref(phone: string): string | null {
    const digits = phone.replace(/[^\d]/g, "");
    if (digits.length < 8) return null;
    return `https://wa.me/${digits.startsWith("00") ? digits.slice(2) : digits}`;
}

/**
 * The queue's "Contact" row action (scale #16): the tenant's phone, WhatsApp and email, read
 * when the dialog opens (`GET /renters/{id}`, which a manager and an accountant may read), and
 * "Log interaction", which records the call on the contract's history like the detail page does.
 */
export default function ContactRenterDialog({ lease, onClose }: { lease: LeaseDetail | null; onClose: () => void }) {
    const t = useTranslations("RenewalSeason");
    const [contact, setContact] = useState<RenterContact | null>(null);
    const [failed, setFailed] = useState(false);
    const [logging, setLogging] = useState(false);
    const [logged, setLogged] = useState(false);

    useEffect(() => {
        if (!lease?.renterId) return;
        let alive = true;
        fetch(`/api/proxy/v1/renters/${lease.renterId}`)
            .then(r => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
            .then((r: RenterContact) => { if (alive) { setContact(r); setFailed(false); } })
            .catch(() => { if (alive) setFailed(true); });
        return () => { alive = false; setContact(null); setLogged(false); };
    }, [lease?.renterId]);

    if (!lease) return null;
    const wa = contact?.phone ? whatsappHref(contact.phone) : null;
    const link = "inline-flex items-center gap-2 text-sm text-primary hover:underline";

    return (
        <>
            <LeaseDialog
                open={!logging}
                title={t("contactTitle", { name: lease.renterName ?? "—" })}
                onClose={onClose}
                onConfirm={() => setLogging(true)}
                confirmText={t("logInteraction")}
                cancelText={t("close")}
                confirmTestId="renewal-contact-log"
                width="sm"
            >
                <div className="space-y-3" data-testid="renewal-contact">
                    <p className="text-xs text-muted">{t("contactFor", { unit: lease.unitIdentifier ?? "—", property: lease.propertyName ?? "—" })}</p>
                    {failed && <p className="text-xs text-error">{t("contactFailed")}</p>}
                    {!contact && !failed && <p className="text-xs text-muted">{t("loading")}</p>}
                    {contact && (
                        <ul className="space-y-2">
                            <li>
                                {contact.phone
                                    ? <a className={link} href={`tel:${contact.phone}`} data-testid="renewal-contact-phone"><Phone size={14} /><bdi dir="ltr">{contact.phone}</bdi></a>
                                    : <span className="text-xs text-muted">{t("noPhone")}</span>}
                            </li>
                            {wa && (
                                <li><a className={link} href={wa} target="_blank" rel="noopener noreferrer" data-testid="renewal-contact-whatsapp"><MessageCircle size={14} />{t("whatsapp")}</a></li>
                            )}
                            <li>
                                {contact.email
                                    ? <a className={link} href={`mailto:${contact.email}`} data-testid="renewal-contact-email"><Mail size={14} /><bdi dir="ltr">{contact.email}</bdi></a>
                                    : <span className="text-xs text-muted">{t("noEmail")}</span>}
                            </li>
                        </ul>
                    )}
                    {logged && <p className="text-xs text-success" data-testid="renewal-contact-logged">{t("interactionLogged")}</p>}
                </div>
            </LeaseDialog>
            {logging && (
                <LogInteractionDialog
                    leaseId={lease.id}
                    onClose={() => setLogging(false)}
                    onSuccess={() => { setLogging(false); setLogged(true); }}
                />
            )}
        </>
    );
}
