# Google Play data-safety draft

This is an implementation-based draft, not a substitute for DTSPL’s legal and operational confirmation. Google Play makes the developer responsible for the final declarations, including backend use, retention, support access and third-party contracts that are not fully visible in the mobile source.

## Shared answers for all three apps

- Data encrypted in transit: **Yes** — production API traffic uses HTTPS.
- Users can request data deletion: **Yes** — use `https://rentaxis.uaenorth.cloudapp.azure.com/en/data-deletion` after it is deployed.
- Ads or advertising SDKs: **No**.
- Sale of user data: **No**, based on the implementation and proposed privacy policy; confirm operationally.
- Account creation inside the app: **No**. Accounts are provisioned by a participating property organisation. Miftah Security uses Firebase phone authentication for an already-provisioned guard.
- Primary purposes: App functionality, account management, fraud prevention/security, developer communications and legal compliance.
- Service providers visible in the implementation: Azure-hosted API/storage; Google Maps (Renter and Manager); Firebase Authentication (Security).

Transfers to contracted processors acting only on DTSPL’s behalf can qualify for Google Play’s “service provider” sharing exception. Confirm each provider contract and actual operational use before marking data as “not shared”.

## Miftah Resident (`com.rentaxis.renter`)

Likely collected data types:

- Personal info: Name, email address, phone number, user IDs and other account/property membership information.
- Financial info: Payment and cheque status, rent transaction history and payment references.
- Location: Approximate and precise location, optional and used only when the user requests nearby-property discovery.
- Photos and videos: Maintenance/ticket attachments chosen by the user.
- Files and documents: Lease, ticket and supporting documents chosen or downloaded by the user.
- Messages: Maintenance requests, ticket replies, meeting notes and related communications.
- App activity: Saved listings, visitor approvals and property-service actions needed for app functionality.
- Other user-generated content: Visitor names, phone numbers, vehicle details and pass information entered by the resident.
- Device or other identifiers: Potentially processed by Google Maps and platform security components; verify against the current Google SDK disclosures.

Collection is a mix of required account/property data and optional location/uploads/payment use. No background location is used.

## Miftah Manager (`com.rentaxis.manager`)

Likely collected data types:

- Personal info: Name, email address, phone number, user IDs, staff/vendor/resident information and organisation membership.
- Financial info: Rent, cheque, payment, settlement, account and transaction records.
- Photos and videos: Cheque scans, listing media and ticket/lease/settlement attachments chosen by the user.
- Files and documents: Lease, ticket, settlement and supporting documents.
- Messages: Tickets, replies, meetings, notes and operational communications.
- App activity: Property, lease, payment, listing, finance and gate-administration actions needed for app functionality and security.
- Other user-generated content: Property, unit, listing, vendor, visitor, policy and guard-assignment details.
- Device or other identifiers: Potentially processed by Google Maps and platform security components; verify against the current Google SDK disclosures.

The Manager app does not request broad photo/video/audio library permission after the release-manifest correction; system pickers grant access only to selected files.

## Miftah Security (`com.rentaxis.security`)

Likely collected data types:

- Personal info: Guard phone number, name, user ID, role and assigned-property membership.
- Photos and videos: Walk-in visitor photos captured by the guard.
- App activity: Pass scans, approval actions, visitor admission and access events needed for security and app functionality.
- Other user-generated content: Visitor name, phone number, vehicle, purpose, unit and pass data.
- Device or other identifiers: Firebase Authentication and Google Play services may process device/security identifiers; verify against current Firebase disclosures.

Camera access is required for QR scanning. The app does not declare location permission and does not collect location through the mobile implementation.

## Items requiring owner confirmation before submission

1. Whether any support, analytics, monitoring or logging system outside this repository receives personal data or persistent identifiers.
2. Actual retention periods for leases, financial records, access logs, visitor photos, support tickets and deleted accounts.
3. Whether DTSPL or participating property organisations use collected data for marketing, profiling or any purpose beyond those listed above.
4. Whether any information is shared with third parties outside the service-provider exception.
5. Current Firebase, Google Maps and Azure data-safety/privacy disclosures and contractual roles.
6. The official privacy/support contact and legal entity/address to publish.
