# Tutorial 03 review

- Tutorial: Roles, permissions, and organisation switching
- Environment: production synthetic tenant `RentAxis Tutorial Studio 2026-09-03`
- Narration: Azure Speech `en-US-JennyNeural` with friendly style
- Candidate video: `tutorials/output/03-roles-permissions-and-organization-switching.mp4`
- Subtitles: `tutorials/output/03-roles-permissions-and-organization-switching.srt`

## Review result

Capture and audio rendering completed, but the tutorial is not approved for
publication yet. The first few seconds of the rendered candidate show the
production loading shell before the dashboard settles. The super-admin
organisation switcher also visibly contains older synthetic/test tenants.

The deployed tenant-label fix is confirmed in the property-manager and renter
scenes: the footer shows the active tutorial tenant name (truncated to fit) with
the `Organization` subtitle. The earlier generic-footer observation is retained
in `tutorials/ui-bug-log.md` as the pre-deployment reproduction.

The candidate MP4 and its raw scene clips remain available for review. Do not
delete any production tenant based on this note without explicit confirmation of
the exact tenant names and IDs to remove.
