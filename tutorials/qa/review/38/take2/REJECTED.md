# REJECTED 38 take 2 (2026-09-29)

Gates passed. Frame review: at cue 26 ("Now open Revenue recognition", ~87 s) the frame is the
contract page loading spinner: going back from the CRT journal page reloads the contract. Cue 38
("In the Trial Balance") still shows the CBR journal (scene 7 started 1.9 s late: the scenes were
weighted to the narration, but the runner spreads the extra 2 s of tail across all of them).

Fix: the CRT and CIL journals are read from the contract's own ledger blocks (no navigation away
and back), the Tenant Ledger is opened from the Accounting rail, and the last scene carries the 2 s tail.
