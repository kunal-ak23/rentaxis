---
title: Chart of Accounts
description: Understanding and managing your accounting structure
category: finance
roles: [TENANT_ADMIN]
order: 1
relatedTour: finance-overview
---

## Chart of Accounts

The Chart of Accounts is the foundation of your financial tracking in RentAxis.

### Account Types

RentAxis uses standard double-entry accounting with five account types:

| Type | Purpose | Example |
|------|---------|---------|
| **Asset** | What you own | Bank Account, Rent Receivable |
| **Liability** | What you owe | Security Deposits Held |
| **Equity** | Owner's investment | Owner's Capital |
| **Income** | Money earned | Rental Income, Late Fees |
| **Expense** | Money spent | Maintenance, Insurance |

### Managing Accounts

1. Go to **Finance > Chart of Accounts**
2. View all accounts organized by type
3. Click **Add Account** to create a new account
4. Each account has a code, name, type, and optional description

### How the Tree is Organised

Accounts form a tree: each account names its parent, group accounts hold children, and only the leaves are posted to. A leaf inherits its parent group's type, and it cannot be given a different one.

- **Group accounts** (for example *Current Assets*, *Bank*, *Security Deposits*) are headings. You cannot post to them.
- **Leaf accounts** are where entries land. Most of them are created per property, so *Bank* holds one leaf per building rather than one shared account.
- An **alias** is an optional short name you can search and pick an account by, without changing its code or name.
- An account cannot be deleted once it carries journal lines or is mapped to a role.

### Account Roles and Mappings

Roles tell RentAxis which account to post to when it raises an entry — rental income, rent receivable, bank, security deposits, and so on. You never pick accounts entry by entry; you map the roles once.

- **Settings > Account template** sets, for each role, the name pattern and the parent group under which each property's leaf is created.
- A property's **Accounts** tab shows the leaf resolved for each role on that property, and lets you re-map one to a different account or generate the ones that are missing.
- A role with no property-level mapping falls back to the organisation-wide default account for that role.

### Journals, Not Edits

Posted entries are immutable. A mistake is corrected by reversing the entry from **Finance > Journal Vouchers**, which posts a mirror entry — the original stays on the books and is marked reversed.
