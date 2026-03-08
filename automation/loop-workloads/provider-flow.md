# Provider Flow Loop

Scope:
- Work only on document provider behavior and provider-focused Android instrumentation.
- Prioritize search metadata, refresh behavior, MIME handling, and end-to-end provider tests.

In scope:
- `GarlandDocumentsProvider`
- provider-related instrumentation tests
- MIME-aware provider handling
- fake local harnesses used by provider tests

Out of scope:
- unrelated activity polish
- background worker internals unless provider correctness depends on them
- roadmap edits unless a provider todo is fully complete

Success signals:
- provider behavior is better covered end to end
- provider tests target realistic user flows
- provider-specific gaps in `NEXT_WAVE.md` move toward closure
