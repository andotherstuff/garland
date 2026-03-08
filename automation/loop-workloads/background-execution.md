# Background Execution Loop

Scope:
- Work only on WorkManager sync and restore correctness.
- Prioritize duplicate-job prevention, retry semantics, state persistence, and worker tests.

In scope:
- `GarlandWorkScheduler`
- `PendingSyncWorker`
- `RestoreDocumentWorker`
- upload/download executor interactions with workers
- unit or instrumentation tests directly tied to background execution

Out of scope:
- unrelated UI polish
- provider search polish unless needed for background state propagation
- README rewrites or broad roadmap cleanup

Success signals:
- background jobs do not obviously duplicate the same document work
- retry vs permanent failure behavior is clearer and tested
- queued/running/failure diagnostics do not lose useful state without replacement
