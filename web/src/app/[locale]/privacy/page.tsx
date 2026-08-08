import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Privacy Policy | Miftah",
  description: "Privacy policy for the Miftah mobile applications and RentAxis services.",
};

const sections = [
  {
    title: "Information we handle",
    body: [
      "Account and identity information, including your name, email address, phone number, role, organisation and property membership.",
      "Property-service information, such as leases, payment records and transaction references, maintenance requests, messages, meetings, listings, visitor passes and access events.",
      "Content you choose to upload, such as maintenance photos, documents, cheque images, visitor photos and vehicle details.",
      "Optional location information in the Miftah Renter app when you ask to find or sort nearby properties. We do not use location in the background.",
      "Camera access when you choose to capture an attachment, scan a cheque, photograph a walk-in visitor or scan a visitor pass.",
      "Technical, diagnostic and security information needed to operate, protect and troubleshoot the service.",
    ],
  },
  {
    title: "How we use information",
    body: [
      "We use information to authenticate users, provide property and tenancy services, manage authorised payment and cheque records, manage maintenance and communications, operate visitor access, prevent abuse, support users and comply with legal obligations.",
      "We do not sell personal information and we do not use personal information for third-party advertising.",
    ],
  },
  {
    title: "Sharing and service providers",
    body: [
      "Information is available to the property owner, manager or organisation that provides your Miftah account, according to your assigned role.",
      "We use contracted service providers for cloud hosting and storage, Firebase phone authentication in Miftah Security, and Google Maps in Miftah Renter and Miftah Manager. These providers process information only to deliver their services and under their own applicable privacy terms.",
      "We may disclose information when required by law, to protect people or property, or in connection with a lawful business reorganisation.",
    ],
  },
  {
    title: "Retention, deletion and security",
    body: [
      "We retain information for as long as the account, tenancy or property-service relationship requires it and for applicable financial, contractual, security and legal retention periods.",
      "Accounts are normally provisioned by a property or organisation administrator. You can ask that administrator to correct or delete your account, or use our public data-deletion request page. Some financial, lease or access records may need to be retained when the law or a legitimate security requirement applies.",
      "We use encrypted network connections, access controls and secure on-device credential storage. No security measure can guarantee absolute protection.",
    ],
  },
  {
    title: "Children and changes",
    body: [
      "Miftah is intended for adults and authorised property staff. It is not directed to children under 18.",
      "We may update this policy as the service or legal requirements change. The effective date below identifies the latest version.",
    ],
  },
];

export default function PrivacyPage() {
  return (
    <main className="min-h-screen bg-[#0d0d0d] px-6 py-16 text-[#f7f2e8]">
      <article className="mx-auto max-w-3xl">
        <p className="mb-5 text-sm font-semibold uppercase tracking-[0.35em] text-[#e5b83d]">Miftah</p>
        <h1 className="font-serif text-4xl font-semibold md:text-5xl" style={{ color: "#f7f2e8" }}>Privacy Policy</h1>
        <p className="mt-5 text-sm text-[#bcb5a8]">Effective 7 August 2026</p>
        <p className="mt-8 text-lg leading-8 text-[#ddd6ca]">
          This policy explains how DTSPL handles information through the Miftah Renter, Miftah Manager and Miftah Security mobile applications and the related RentAxis services.
        </p>

        <div className="mt-12 space-y-10">
          {sections.map((section) => (
            <section key={section.title}>
              <h2 className="text-2xl font-semibold" style={{ color: "#f1c84c" }}>{section.title}</h2>
              <div className="mt-4 space-y-4 text-base leading-7 text-[#d7d0c4]">
                {section.body.map((paragraph) => <p key={paragraph}>{paragraph}</p>)}
              </div>
            </section>
          ))}
        </div>

        <section className="mt-12 border-t border-[#4a3b17] pt-8">
          <h2 className="text-2xl font-semibold" style={{ color: "#f1c84c" }}>Contact</h2>
          <p className="mt-4 leading-7 text-[#d7d0c4]">
            Privacy questions and requests can be sent to{" "}
            <a className="text-[#f1c84c] underline" href="mailto:reports@theplahouse.com">reports@theplahouse.com</a>.
          </p>
          <p className="mt-3 leading-7 text-[#d7d0c4]">
            To request deletion, visit <a className="text-[#f1c84c] underline" href="/en/data-deletion">the Miftah data-deletion page</a>.
          </p>
        </section>
      </article>
    </main>
  );
}
