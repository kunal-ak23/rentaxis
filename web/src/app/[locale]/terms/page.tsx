import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Terms of Use | Miftah",
  description: "Terms governing use of the Miftah mobile applications.",
};

export default function TermsPage() {
  return (
    <main className="min-h-screen bg-[#0d0d0d] px-6 py-16 text-[#f7f2e8]">
      <article className="mx-auto max-w-3xl">
        <p className="mb-5 text-sm font-semibold uppercase tracking-[0.35em] text-[#e5b83d]">Miftah</p>
        <h1 className="font-serif text-4xl font-semibold md:text-5xl">Terms of Use</h1>
        <p className="mt-5 text-sm text-[#bcb5a8]">Effective 7 August 2026</p>
        <div className="mt-10 space-y-8 text-base leading-7 text-[#d7d0c4]">
          <p>Miftah is a property-service suite supplied by DTSPL to authorised residents, managers and security staff. Your access may also be governed by an agreement with the property or organisation that issued your account.</p>
          <p>You must provide accurate information, protect your sign-in credentials and use the applications only for lawful property, tenancy, payment, maintenance and visitor-access purposes. You may not misuse another person’s account, bypass access controls, interfere with the service or upload unlawful or harmful content.</p>
          <p>Payment and cheque information reflects records managed by the relevant property organisation. Property, lease, charge, visitor and access decisions are made by that organisation; Miftah presents and processes authorised instructions.</p>
          <p>The service may change, be suspended for maintenance or be restricted when required for security, policy or legal reasons. To the extent permitted by law, DTSPL is not liable for indirect or consequential loss or for decisions made by a property organisation or third-party provider.</p>
          <p>We may suspend access for misuse or when instructed by the account-providing organisation. Applicable mandatory consumer rights are not limited by these terms.</p>
          <p>Questions can be sent to <a className="text-[#f1c84c] underline" href="mailto:reports@theplahouse.com">reports@theplahouse.com</a>. Our <a className="text-[#f1c84c] underline" href="/en/privacy">Privacy Policy</a> explains how information is handled.</p>
        </div>
      </article>
    </main>
  );
}
