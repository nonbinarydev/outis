[Outis](../../README.md) › [Docs](../README.md) › Decisions

# Decision records

Why this project is shaped the way it is. Each record states a decision that would otherwise have to be
reverse-engineered from the code, along with what it cost.

These are deliberately short and deliberately immutable. A record is not edited when the world changes —
a new record supersedes it, and the old one is marked as superseded, so the reasoning at the time stays
readable. That is the whole point of keeping them.

| # | Decision | Status |
|---|---|---|
| [0001](0001-engine-agnostic-core.md) | No platform or Compose types in the `:core` API | Accepted |
| [0002](0002-api-scope-by-signature-leakage.md) | Dependency scope is decided by public-signature leakage | Accepted |
| [0003](0003-analytics-adapters-bind-natively.md) | Analytics adapters bind to the native player, not to `PlayerEvent` | Accepted |
| [0004](0004-static-analysis-split.md) | Three analysers, with distinct responsibilities | Accepted |
| [0005](0005-branching-strategy.md) | `main` + `development` + short-lived topic branches | Accepted |

## Writing a new one

Copy the shape of an existing record: **Context** (the forces, with evidence), **Decision** (what was
chosen, in the active voice), **Consequences** (what this costs, including the bad parts). Number it
sequentially and add it to the table above.

Record a decision when it is not obvious from reading the code, when it was contested, or when the
obvious alternative was rejected for a reason that will not survive in anyone's memory. Do not record
things the code already says plainly.
