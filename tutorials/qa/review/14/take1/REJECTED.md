# REJECTED 14 take 1 (2026-09-29)

The capture stopped in scene 2: the proof counted the Proposed rows as soon as the queue box was
visible, before its rows had loaded ("Expected 2 proposed penalties, found 0"). No video. Scene 1
also ran 10 s long: the first page after the snapshot restore loads slowly on a cold backend.

Fixes: wait for the second row before counting; the runner opens the scenario's `warmup` pages
(read-only, unrecorded) before the take.
