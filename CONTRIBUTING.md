<!--
SPDX-FileCopyrightText: 2026 BluffWorks LLC
SPDX-License-Identifier: GPL-3.0-only
-->

# Contributing

## Sign-off and the CLA

**Every commit is signed off** (`git commit -s`, the DCO sign-off) — that is this repository's
commit convention and it costs nothing; CI checks that the trailer is present.

**Code contributions also need the CLA** in `CLA.md`. By opening a pull request that changes
code, you agree to it. It is one page, and section 4 says in plain words what it is for.

**Device evidence and test fixtures need no CLA.** Real-session NDJSON logs and other measured
device data are contributed under CC0-1.0 — a public-domain dedication, which already grants
strictly more than the CLA does. There is nothing left for a CLA to add, so none is asked for.
Sign off the commit and that's it.

Inbound code is licensed GPL-3.0-only, same as the rest of this repository, plus the CLA grant
described in `CLA.md`.

Say plainly what this means in practice: **the maintainer also ships this code in a proprietary
app under the CLA grant.** That is the deal, stated up front, not a fine-print surprise — the
GPL-3.0-only license on this repository does not restrict what the maintainer does with it;
it restricts what *other* redistributors do.

## A hard rule

Device quirk configs are declarative — a closed set of typed scalars and enums matched against
device identifiers. No expression field, no formula, no script, ever. This keeps every quirk
auditable at a glance and keeps the recorder from becoming an interpreter for arbitrary code
pulled from a config file.

## Scope

This module is the recording engine only: capture, storage, and measured facts. Analysis,
notifications content, and UI are the host application's job — see `README.md`'s "What it is
not." PRs that add analytics or presentation logic here are out of scope; the seam is
`RecorderHost`.

## The seam's stability rule

`RecorderHost` is how the recorder reaches the applications that host it, and most of those
applications are not in this repository — you cannot read them, and a change here that breaks one
of them breaks it silently, somewhere you will never see the build fail. One rule keeps that from
happening:

> **Every new member of `RecorderHost` ships with a default implementation.** A member with a
> default is additive — every existing host still compiles and opts in only if it wants to. A
> member without one is a breaking change: it needs a major version bump and a `CHANGELOG.md`
> entry, and a PR that adds one will be asked for the default instead.

`deviceKind` and `gaugeProfile()` are the worked examples. Both were added within two days of each
other to serve a host that is not here, and both shipped with a default naming the phone's own
prior behaviour, so no existing implementation changed by a line. Prefer a default that describes
what the recorder did *before* the member existed — that is what makes the addition invisible to
everyone who does not need it.

**The rule covers the types `RecorderHost` names, not just its members.** `ChannelLabels`,
`HostContent`, `RecorderState` and its subclasses, `SessionRecap`, `GaugeProfile`,
`RawLine.Sample`: a host constructs some of these, reads others, and calls `copy()` on them.
Removing a property, renaming one, or adding one without a default breaks a host as surely as
changing a method signature does — and nothing in this repository will tell you, because the only
consumer it can compile is `sample/`, which does not exercise every constructor.

The case that made this explicit: `GaugeProfile` lost six constructor parameters when the
analyzer's calibration constants moved out of the open module. Nothing broke, because no host
outside this repository existed yet. But `profile.copy(noiseFloorW = …)`, or a host constructing a
profile for its own gauge, would have stopped compiling — and the rule as written then said "every
new *member* of `RecorderHost`", which did not reach it. So: a change to a named type is a change
to the seam. Additive with a default, or a major version bump and a `CHANGELOG.md` entry.

The `sample/` app is the other half of this guarantee, and the reason it is built in CI rather
than just shipped: it is a real `RecorderHost` implementation that a seam change has to keep
compiling. If your change breaks the sample, it breaks every host.

## Device evidence

Real hardware quirks are why this project exists. If you can contribute an NDJSON session from a
device or charger the fixture corpus doesn't cover, see `README.md`'s "Contributing device
evidence" section — **including the notice about what a session log contains**, since a CC0
dedication is permanent.

## Reporting a problem

Bugs and device quirks go to the issue tracker; the templates ask for what a report actually
needs. Security-sensitive findings go through GitHub's private advisories instead — see
`SECURITY.md`.
