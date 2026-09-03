# Production tutorial UI bug log

This log records issues found while validating the persistent Tutorial Studio tenant. Confirmed defects are kept separate from visual observations until reproducible evidence exists.

| Date | Area | Evidence | Status |
| --- | --- | --- | --- |
| 2026-08-26 | Listings / cover media | All four listing thumbnails returned HTTP 404 from the stored Azure Blob URLs; the UI rendered broken-image icons. | Fix branch `codex/fix-listing-thumbnail-url`, verified commit `ca77f79f` (SAS-backed read URLs); PR link: https://github.com/kunal-ak23/rentaxis/pull/new/codex/fix-listing-thumbnail-url |
| 2026-08-26 | Renter directory | The visible View action was a non-interactive span during production validation. | Fix branch `codex/fix-renter-view`, commit `51f1acae`; PR compare link: https://github.com/kunal-ak23/rentaxis/compare/main...codex/fix-renter-view |
| 2026-08-26 | Lease payment schedule | The rightmost payment-schedule columns clipped because the grid column lacked `min-w-0`. | Fix branch `codex/fix-lease-schedule-overflow`, commit `3527d99d`; PR link: https://github.com/kunal-ak23/rentaxis/pull/new/codex/fix-lease-schedule-overflow |
| 2026-08-26 | Contract preview | Production Chromium showed the browser PDF fallback instead of rendering the contract inline. | Reproducible observation; focused fix not yet opened. |
| 2026-08-26 | Manager mobile locale/navigation | During an Android production capture, changing locale and immediately navigating triggered a Riverpod assertion in the router redirect (`Cannot use ref functions after the dependency of a provider changed`). | Fix branch `codex/test-manager-route-coverage`, commit `b84d8b56`; PR link: https://github.com/kunal-ak23/rentaxis/pull/new/codex/test-manager-route-coverage |
| 2026-08-26 | Renter shell | The footer displayed `— Organization` instead of the tenant name for Ahmed Hassan. | Fixed in deployed commit `ebcc8bd4`; verified in Tutorial 03 renter capture. |
| 2026-09-03 | Property Manager shell | The property-manager capture also displayed the generic `Organization` footer instead of the active tutorial tenant name. | Fixed in deployed commit `ebcc8bd4`; verified in Tutorial 03 property-manager capture. |
