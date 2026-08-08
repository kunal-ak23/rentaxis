import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Data Deletion | Miftah",
  description: "Request deletion of a Miftah account and associated personal data.",
};

export default function DataDeletionPage() {
  return (
    <main className="min-h-screen bg-[#0d0d0d] px-6 py-16 text-[#f7f2e8]">
      <article className="mx-auto max-w-3xl">
        <p className="mb-5 text-sm font-semibold uppercase tracking-[0.35em] text-[#e5b83d]">Miftah</p>
        <h1 className="font-serif text-4xl font-semibold md:text-5xl" style={{ color: "#f7f2e8" }}>Account and data deletion</h1>
        <p className="mt-8 text-lg leading-8 text-[#ddd6ca]">
          Miftah accounts are normally created and managed by your property owner, property manager or organisation administrator. You can request deletion through that administrator or directly from DTSPL.
        </p>

        <section className="mt-12">
          <h2 className="text-2xl font-semibold" style={{ color: "#f1c84c" }}>How to submit a request</h2>
          <ol className="mt-5 list-decimal space-y-4 pl-6 leading-7 text-[#d7d0c4]">
            <li>Email <a className="text-[#f1c84c] underline" href="mailto:reports@theplahouse.com?subject=Miftah%20account%20deletion%20request">reports@theplahouse.com</a> with the subject “Miftah account deletion request”.</li>
            <li>Include the Miftah app you use, your registered email address or phone number, and the property or organisation connected to your account.</li>
            <li>We or your organisation administrator may ask for reasonable verification before acting on the request.</li>
          </ol>
        </section>

        <section className="mt-10">
          <h2 className="text-2xl font-semibold" style={{ color: "#f1c84c" }}>What happens next</h2>
          <div className="mt-4 space-y-4 leading-7 text-[#d7d0c4]">
            <p>After verification, we will disable the account and delete or anonymise associated personal data that is not required for an ongoing tenancy, payment, dispute, fraud-prevention, safety or legal-retention purpose.</p>
            <p>Records that must be retained will be restricted to the permitted purpose and deleted or anonymised when the relevant retention period ends. We aim to complete eligible requests within 30 days and will explain if more time is required.</p>
          </div>
        </section>

        <p className="mt-12 border-t border-[#4a3b17] pt-8 text-[#bcb5a8]">
          Read the full <a className="text-[#f1c84c] underline" href="/en/privacy">Miftah Privacy Policy</a>.
        </p>
      </article>
    </main>
  );
}
