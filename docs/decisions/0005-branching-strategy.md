# 5. `main` + `development` + short-lived topic branches

**Status:** Accepted

## Context

The repository already had `main` and `development`, which is gitflow's spine. The question was whether
to adopt the rest of gitflow — `release/` and `hotfix/` branches — or stop there.

Outis has one maintainer.

## Decision

`main` is always releasable and carries the tags. `development` is the integration branch. Work happens
on short-lived topic branches cut from `development` and lands by pull request. `development` merges
into `main` when a release is cut.

Branches are named `type/number-short-description`, where the type is a Conventional Commits type and
the number is the issue: `fix/1-core-api-dependencies`.

No `release/` or `hotfix/` branches.

## Consequences

Pull requests are not ceremony here — they are the only place CI runs. Both the test and detekt
workflows trigger on `pull_request`, so a commit pushed directly to `development` is never tested. That
is the actual argument for the loop, and it is worth stating because a solo maintainer will otherwise
reasonably ask why they are reviewing their own work.

`release/` branches exist to let a team stabilise a release while others keep merging. With one person
there is nobody to isolate from, so they would be pure overhead. An urgent fix against a published
version branches from the tag and merges into both `main` and `development`.

Matching branch prefixes to Conventional Commits types leaves the door open to generating release notes
from history rather than hand-writing `CHANGELOG.md`.

Branch protection must not require approvals: GitHub forbids approving your own pull request, so a
required approval would permanently block every merge.
