# Database migrations (Liquibase): deploy and rollback notes

Changesets live in `backend/src/main/resources/db/changelog/changesets/` and are
append-only: an applied changeset is never edited, a fix is a new changeset.

Production deploys roll forward only. A `rollback` block in a changeset is for local
and test databases; check the notes below before relying on one anywhere else.

## Changesets that cannot roll back

| Changeset | Why | Since |
|---|---|---|
| `129-rent-free-periods` | **129 cannot roll back once rent-free periods exist.** Its rollback restores the old `ck_lease_lines_net` (`net = gross − discount`), which every line with `rent_free_amount > 0` violates, so the `ALTER TABLE … ADD CONSTRAINT` fails. Editing 129 is forbidden; if 129 ever has to be undone, a new changeset must first fold the concession into the discount or remove those lines. | Follow-up ruling R4, 2026-10-17 |

## Before a deploy that adds a CHECK or a data move

Run the read-only preview for that changeset on prod first (`BEGIN TRANSACTION READ ONLY … ROLLBACK`).
The previews are kept outside the repo with the deploy notes (`prod-check-<N>-predeploy.sql`).
For example, changeset 178 adds CHECK constraints on `charge_types.recognition`,
`lease_lines.posted_recognition` and `leases.fee_timing`; its preview must show no row
outside the allowed values, or the deploy fails.
