<!--
SPDX-FileCopyrightText: 2026 BluffWorks LLC
SPDX-License-Identifier: GPL-3.0-only
-->

# Security policy

This is a data-capture library. It runs a foreground service, holds a wake lock, and writes
charging-session logs to an app's private storage — inside whatever application embeds it. A bug
here is a bug in someone else's app, so please report it privately first.

## Reporting

Use GitHub's private vulnerability reporting:
**[Report a vulnerability](https://github.com/AllenBW/chargelog-recorder/security/advisories/new)**
(the *Security* tab → *Report a vulnerability*). That opens a private advisory only the
maintainer can see.

This is a single-maintainer project, so there is no formal SLA. Expect an acknowledgement within
a week. If a week passes with no reply, open a public issue saying only that you are waiting on a
private report — no details — and that will get attention.

Please give us a reasonable window to ship a fix before disclosing publicly. We will credit you
in the advisory and the changelog unless you'd rather we didn't.

## In scope

- Session data reachable from outside the host app's sandbox — a world-readable log or database
  file, an exported component, a `FileProvider` path that grants more than intended, data
  surviving in a location the host did not choose.
- Anything the library writes outside `noBackupFilesDir`, or any path traversal through a session
  file name or a `deviceId` read from an untrusted log.
- Crashes, hangs, unbounded memory, or unbounded disk growth reachable by feeding the recorder a
  malformed or hostile NDJSON log (`Replay`/`Ingest` read files the host may have received from
  another device).
- Any network access at all. The recorder must never make one, and this tree carries no
  `INTERNET` permission — an outbound connection from `:recorder` is a security bug by
  definition.
- A quirk config that behaves as anything other than declarative typed scalars — see
  `CONTRIBUTING.md`'s hard rule.
- Wake-lock or foreground-service behavior that a non-host app can trigger or keep alive.

## Not in scope

- The `:sample` app, which exists to demonstrate the API and is not a product. Please still tell
  us if the *library* misbehaves in it.
- The closed ChargeLog app, its analytics, and its UI — none of that is in this repository. Send
  those through the same private advisory anyway and we will route them.
- A host app that grants the wrong permissions, exports its own components, or misuses
  `RecorderHost`. If our documentation led it there, that is a documentation bug and we do want
  to hear it.
- The privacy content of a session log itself (wall-clock timestamps, screen-state trace, device
  id). That is documented, deliberate, and described in `README.md`; it is a design property, not
  a vulnerability. If it is *undocumented*, that is a real report — tell us.
- Physical-access or rooted-device attacks, where the platform's own sandbox no longer holds.
