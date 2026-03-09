# Garland Agent Workflow

This repo is often worked on by multiple agents at the same time. Default to a worktree-based workflow, treat `main` as the delivery branch, and prefer a commit-heavy workflow over long-lived local state.

## Git defaults for this repo

- Always use a dedicated git worktree for non-trivial work when another agent may be active.
- Never do feature work directly in a shared dirty checkout if a separate worktree is possible.
- Before pushing, inspect `git remote -v` and confirm `origin` is `https://github.com/andotherstuff/garland.git`.
- Deliver work in small, reviewable increments: after each meaningful chunk that builds, tests, or clearly moves the task forward, create a commit instead of waiting for one large end-of-task snapshot.
- Default to pushing completed commits promptly to `origin/main` so the shared branch stays current and other agents can build on the latest verified state.
- Do not leave completed work stranded on a side branch unless the requester explicitly asks for that.

## Commit cadence

- Prefer frequent commits over large batches. A good default is to commit after every self-contained fix, UI pass, refactor, verification repair, or docs update that you would be comfortable shipping by itself.
- If a task spans multiple layers, commit at the layer boundaries once each layer is coherent and verified.
- If verification fails, keep iterating until the work is green, then commit the repaired state instead of carrying a large pile of uncommitted edits.
- When multiple commits are created for one task, keep each commit scoped and push them in order to `origin/main` once verified.

## Conflict handling

- Expect conflicts because multiple agents may touch the same files.
- Resolve conflicts surgically. Keep the intended behavior from both sides when possible.
- Never discard another agent's verified change just to make the merge easy.
- After resolving conflicts, rerun the relevant tests before committing and pushing.

## Safety rules

- Do not use destructive git commands unless the requester explicitly asks.
- Do not overwrite or revert unrelated uncommitted work in a shared checkout.
- If the shared checkout is dirty, create or use a separate worktree, finish there, then merge back into `main`.
- If a push is blocked by branch protection, auth, or a higher-priority instruction, report the blocker clearly.

## Verification defaults

- Prefer the smallest correct verification set for the files you changed.
- Do not run `./gradlew assembleDebug` by default for every app change; Garland builds are expensive and can bog down the machine.
- Default to targeted tests such as `./gradlew testDebugUnitTest`, focused Gradle test targets, `./gradlew compileDebugAndroidTestKotlin`, and `./automation/cargo_capped.sh test` when they cover the change.
- **Never run bare `cargo build` or `cargo test`** — always use `./automation/cargo_capped.sh` which applies nice/ionice and parallelism limits. The machine has 0 swap; uncapped Cargo builds hang it.
- Escalate to `./gradlew assembleDebug` or `automation/verify_alpha_no_device.sh` only for release prep, packaging/build-system changes, manifest/resource changes that need APK validation, JNI integration changes, or bugs that reproduce only in a built app.
- Keep thorough no-device verification for release candidates and explicit sign-off passes, not routine iteration.
- Use verification as the trigger for commit cadence: once the relevant checks pass for the current chunk, commit it and push it to `main`.

## Delivery expectation

- Default finish line for this repo: verified changes committed and pushed to `origin/main`.
- For larger tasks, prefer several verified commits pushed during the work rather than one final commit at the end.
- Only skip push-to-main delivery if the requester explicitly says not to, or if a real blocker prevents it.
