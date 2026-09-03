# Tutorial 03 review

- Tutorial: Roles, permissions, and organisation switching
- Environment: production synthetic tenant `RentAxis Tutorial Studio 2026-09-03`
- Narration: Azure Speech `en-US-JennyNeural` with friendly style
- Candidate video: `tutorials/output/03-roles-permissions-and-organization-switching.mp4`
- Subtitles: `tutorials/output/03-roles-permissions-and-organization-switching.srt`

## Review result

Capture and audio rendering completed, but the tutorial is not approved for
publication yet. The branded opening card now covers the production startup
delay, and representative frames at 3s, 80s, and 160s show loaded screens with
the expected role callouts. The super-admin organisation switcher still
visibly contains older synthetic/test tenants.

The deployed tenant-label fix is confirmed in the property-manager and renter
scenes: the footer shows the active tutorial tenant name (truncated to fit) with
the `Organization` subtitle. The earlier generic-footer observation is retained
in `tutorials/ui-bug-log.md` as the pre-deployment reproduction.

The candidate MP4 and its raw scene clips remain available for review. Do not
delete any production tenant based on this note without explicit confirmation of
the exact tenant names and IDs to remove.

Legacy synthetic entries observed in the switcher (cleanup allow-list only):

| Name | ID |
| --- | --- |
| Al Ashram Demo Account | `5432aca2-cd9c-4431-a131-22e67a5b72b0` |
| TEST-E2E 2026-08-25 mt8qfifr | `5586e405-3fe9-4ebf-8542-a742864b6a1a` |
| RentAxis Tutorial Demo | `a5c3ad23-9abe-4435-a0b5-929559d516e7` |
| RentAxis Tutorial Studio | `624fb5bb-902b-415f-9279-20a914eb52c0` |
| Miftah Demo Tutorial 2026-08-27-zmkbe | `d49a5016-cced-4f81-a4f3-354714120c65` |

The fresh recording tenant is `RentAxis Tutorial Studio 2026-09-03`
(`61b648b7-a380-4e8a-b8e6-e7f4cb4df452`) and must be retained. The separate
`AI Ashram` entry is not part of this cleanup allow-list.
