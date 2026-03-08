# Diagnostics and Release Hardening Loop

Scope:
- Work only on diagnostics UX, diagnostics persistence clarity, and release-readiness hardening.
- Prioritize clearer failure messages, diagnostics screens/sections, and tests that help a first testing release.

In scope:
- diagnostics data formatting and persistence
- diagnostics UI in `MainActivity`
- tests for release-facing behavior
- small release hardening fixes directly related to diagnostics and tester experience

Out of scope:
- provider-only behavior unless needed by diagnostics
- broad architectural rewrites
- publishing a release or changing git metadata

Success signals:
- tester-visible status is clearer
- diagnostics better explain relay/server outcomes
- release readiness blockers become easier to validate
