# Test corpus

Real charging sessions, recorded by the maintainer's own devices, committed byte-for-byte so
tests exercise the parser against what hardware actually wrote — including the recovered/
directory's truncated and interleaved logs, which are the point. These files never change:
`RecorderCorpus` names them and golden tests pin them, so a regenerated file is a test failure,
not an update.

Everything in this directory is dedicated to the public domain under CC0-1.0.

## What these files disclose, on purpose

They are real recordings, published knowingly:

- `watch/real/session-1788175545922.ndjson` carries `"deviceId":"9a500fa5a167123cc127c8790a9ccc31"`
  — the maintainer's own install id on the watch that recorded it. It is a random 128-bit value
  the app generates on first run; it identifies that installation, not the hardware or a person.
- Headers carry `deviceModel`, `osRelease`, and `appVersion`; samples carry wall-clock
  timestamps and a 1 Hz screen-state trace for the session's duration.

If you contribute a session log of your own, the same fields are in yours — read the
"Contributing device evidence" section of the top-level README before sharing one, and decide
knowingly, as we did.
