# Garland Agent Workflow

This repo is often worked on by multiple agents at the same time. Default to a worktree-based workflow and treat `main` as the delivery branch.

## Git defaults for this repo

- Always use a dedicated git worktree for non-trivial work when another agent may be active.
- Never do feature work directly in a shared dirty checkout if a separate worktree is possible.
- Before pushing, inspect `git remote -v` and confirm `origin` is `https://github.com/andotherstuff/garland.git`.
- When a task is complete, update local `main`, merge the task branch into `main`, resolve conflicts carefully, run the relevant verification, and push to `origin/main`.
- Do not leave completed work stranded on a side branch unless the requester explicitly asks for that.

## Conflict handling

- Expect conflicts because multiple agents may touch the same files.
- Resolve conflicts surgically. Keep the intended behavior from both sides when possible.
- Never discard another agent's verified change just to make the merge easy.
- After resolving conflicts, rerun the relevant tests before pushing.

## Safety rules

- Do not use destructive git commands unless the requester explicitly asks.
- Do not overwrite or revert unrelated uncommitted work in a shared checkout.
- If the shared checkout is dirty, create or use a separate worktree, finish there, then merge back into `main`.
- If a push is blocked by branch protection, auth, or a higher-priority instruction, report the blocker clearly.

## Delivery expectation

- Default finish line for this repo: merged into `main` and pushed.
- Only skip merge-and-push if the requester explicitly says not to, or if a real blocker prevents it.
