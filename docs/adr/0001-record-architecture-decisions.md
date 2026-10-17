# 1. Record architecture decisions

- Status: accepted
- Date: 2026-10-17

## Context

`PROJECT.md` is the design brief and will keep changing as the project is built in public. The
decisions behind it, and the ones its open questions still have to settle, need a durable record
of *why*, not just *what*, so a choice isn't silently reversed later or argued again from scratch
by someone who wasn't there.

## Decision

Use lightweight ADRs: one Markdown file per decision under `docs/adr/`, numbered sequentially,
following `0000-adr-template.md`, and listed in `docs/adr/README.md`. `PROJECT.md` stays the
living description of the system; ADRs capture point-in-time decisions and their rationale. When an
ADR settles an open question from `PROJECT.md`, that section of the brief is updated to point here.
An architecture-changing pull request carries its ADR in the same pull request.

## Consequences

- Every non-obvious choice gets a short, reviewable home that contributors can find.
- ADRs are append-only. A reversal is a new ADR that supersedes the old one, and the old one stays
  with its status changed.
- A small overhead per decision, acceptable for a project meant to be followed and learned from.
