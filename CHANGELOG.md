<!--
SPDX-FileCopyrightText: 2026 BluffWorks LLC
SPDX-License-Identifier: GPL-3.0-only
-->

# Changelog

Notable changes to the ChargeLog recorder. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## Versioning

Three version numbers travel with this library.

**The library's version is the app's.** Every ChargeLog release `X.Y.Z` is also a recorder release
`X.Y.Z`: the section below is stamped as part of the app's release, and this repository carries the
tag `vX.Y.Z` and a GitHub Release whose notes are that section, published by the same sync that
carries it. A release with no recorder change still gets a section saying so, so the two numbers
never drift. A sync tags only the newest release it carries, and a tag is never added afterwards,
so a release that was cut and superseded before the next sync has a section here and no tag: 0.4.0,
0.6.0, 0.6.1 and 0.6.2 are such releases, and their changes are in the next tag (`v0.5.0` and
`v0.7.0`). Sections before 0.4.0 predate the tags. A tag marks the tree its sync carried, which can
include work still under Unreleased at the time; `v0.5.0` does. What a host compiles against is the
`RecorderHost` seam and the public API of `capture/`, `data/`, and `measure/`; a change that makes
a host change code is called out in its section. There is no published Maven artifact — consumers
vendor the source or depend on the module directly — so the tag is the coordinate.

**The NDJSON schema version** (`schema` in every session log's header, currently **2**) is a plain
integer with its own contract, described in `docs/ndjson-format.md`: decoding is tolerant, so an
added nullable field does *not* bump it. It bumps only for a change an older reader cannot handle
safely. A fixture recorded years from now must still be readable, so this number is the one that
actually constrains us.

**The Room database version** (currently **6**) is a private implementation detail. The database
is a projection rebuilt from the NDJSON logs, which are the source of truth; a host that wipes it
loses nothing.

`BuildConfig.VERSION_NAME` from the *host* app — not from this library — is what the recorder
writes into each session header's `appVersion`. A fixture therefore records which build of which
app produced it, which is why the field is there.

## [Unreleased]

### Fixed

- The record switch's idle post runs on `Dispatchers.IO`, as the recaps' posts already did, not
  on `Dispatchers.Default`. It calls the host's `content`, and on a two-core device a slow
  `content` beside a pump busy writing left a re-plugged session's first tick with no worker, so
  its notification waited on the pump. Display only: `RecorderHost`, the NDJSON schema (2) and
  every public signature are unchanged.
- `LiveFeed.Snapshot.recentWatts` reads the current at the scale of the gauge heading the session,
  and rescales when the recorder learns that gauge speaks mA mid-charge. It read every current as
  µA, so on a milliamp phone or watch each figure was a thousand times low. No signature changes.
- The sample host reads its live watts at the gauge's scale: `CapturePrefs.learnedGaugeId` (falling
  back to `gaugeProfile()`) at start, then `onGaugeRefined`. It printed "Recording — 0.0 W" on a
  milliamp phone. A host that renders a live sample's watts needs the same: the two-argument
  `Units.watts` is for a current known to be µA, which a phone's often is not.

## [1.0.0] — 2026-10-19

1.0.0 is the app's open-beta number, not an API-stability promise: this library's version is the
app's, so a change that makes a host change code can still come in any release, and its section
says so.

### Added

- `CaptureDao.project(session, samples)`: one session's row and samples in a single transaction.
  `Replay.reconcile`, `Replay.projectAll` and `Replay.ingestExternal` now write through it alone.
  A default interface method, so a host's own `CaptureDao` fake inherits it.
- `IngestReasons.TOO_LARGE`, for an import stream over 128 MiB or a log past `Replay.MAX_SAMPLES`
  samples. A host that maps reasons to copy needs a line for it; until then its fallback shows.
- `Replay.MAX_SAMPLES`: the most samples `Replay.parse` reads from one file, four days at one
  sample a second, and `Replay.TooManySamples`, the `IOException` it throws past that. A host that
  calls `parse` and catches `IOException` skips such a file as it skips an unreadable one; one that
  has to say why catches `TooManySamples` first.
- An `ingest_too_many_samples` event (`EventKinds.INGEST_TOO_MANY_SAMPLES`), so a session missing
  for its size can be traced. The first projection pass in a process to refuse a file writes it,
  and the refusal is remembered only while that process lives, so each app start that meets the
  file writes it again.
- `SettleDetector`'s `flatHoldMs` constructor parameter: how long a charge must sit with nothing
  changing to settle as a hold, 30 minutes by default. A host that builds its own detector and
  passes nothing gets the new rule below.

### Changed

- CI runs Android lint on the library (`./gradlew :recorder:lintDebug`), and three checks are
  errors: `NewApi` (a call above `minSdk` 31 with no version check), `MissingPermission` and
  `UnspecifiedRegisterReceiverFlag`. A pull request that adds one fails until it is guarded; run
  the same task locally first (`BUILDING.md`). The library's own code had none.
- The unit tests re-run when only a committed Room schema under `recorder/schemas/` changes. A
  hand-edited schema used to leave them up to date, and the schema pin unrun.
- The public `build` job installs its SDK packages with the runner's own `sdkmanager`.
  `android-actions/setup-android`'s `sdkmanager tools` had stopped resolving, which left the job
  red from the 2026-09-16 sync through `v0.7.0`.
- A test task that hangs fails after 20 minutes instead of holding the build open.
- The sample host's manifest names `dataExtractionRules` that exclude every domain from cloud
  backup and device-to-device transfer, beside `allowBackup="false"`. From targetSdk 31 on,
  Android ignores `allowBackup` for device-to-device transfer, which would carry `DeviceIdentity`'s
  prefs file to a new phone. A host needs both attributes; `DeviceIdentity`'s KDoc now says so.
  Host-side only: `RecorderHost`, the NDJSON schema (2) and the Room projection (6) are untouched.
- The `FakeCaptureDao` test fixture behaves as Room does: samples are replaced by
  `(sessionId, elapsedRealtimeMs)` instead of appended, `samples` comes back in elapsed order and
  `sessions` newest first. A test that relied on duplicated samples or insertion order changes with
  it. This changes neither `RecorderHost`, the NDJSON schema (2) nor the Room projection (6).

### Fixed

- A charge the device holds short of the user's target now settles. `SettleDetector` settles a
  charge whose level, status, plug type and charging status have all stayed the same for 30
  minutes, at any level and under any target, and resumes it on the first sample in which any of
  them differs. A phone holding its charge below the target while still reporting it charging (an
  OEM charge limit at 80–85 %, an Adaptive Charging hold under a 100 % target), or a watch holding
  it there at `NOT_CHARGING`, met none of the three settle predicates, so it was sampled every
  second until the unplug, holding the wake lock up to its three-hour safety timeout: 3,600 lines
  an hour, and about four days of it passed `Replay.MAX_SAMPLES`. Now it is sampled on battery
  broadcasts after the first half hour, as a full charge is. A normal charge is unchanged: no
  level step in the recorder's captures passes 17 minutes, and it still settles two minutes into
  `FULL`. A charge climbing slower than about 2 % an hour is taken for a hold, and is back on the
  tick at its next level step. A
  thermal pause longer than half an hour now settles too, and resumes when its status changes or
  the level moves. The settle writes the same `capture_policy settled` line;
  it can now come at any level, so `settled` no longer implies the charge reached its target, and
  the samples after it arrive on battery broadcasts only; `RecorderHost`, the
  NDJSON schema (2) and the Room projection (6) are untouched. Closing and reopening the log at a
  size or age (rotating sessions) was considered and rejected: it bounds each file but not what is
  written, and splits one plug-in into several sessions. Docs:
  `docs/recording-service-threading.md`, "A hold below the target".
- `RecordingService` no longer shows its idle notification over a charge it is recording. The idle
  posts that follow a recap (the last session's, read at service start, and each closed session's)
  and the record switch's looked whether a session was recording before building their content,
  then posted without looking again. A plug-in that landed in between left the idle text over the
  live session until the notification's five-second gate let a later sample through; on the
  charger at service start that happened whenever the recap looked before the capture pump had
  opened the session. They now look again inside the notification's lock, and are dropped once a
  session is open. A sample's post is dropped the same way once its unplug has been seen, so a
  plug-in unplugged within milliseconds no longer leaves the recording text over the idle
  notification. A stopped service also posts nothing more: a recap still being read when the
  service stopped used to post afterwards, an ordinary notification that no running service owned.
  `RecorderHost`, the NDJSON schema (2) and the Room projection (6) are untouched. Docs:
  `docs/recording-service-threading.md`, "One post at a time" and "Teardown".
- `RecordingService`'s idle notification names the newest finished charge. Its recaps are read off
  the main thread, one at service start (the newest log on disk) and one for each closed session,
  and each used to replace the last when its read finished: a start-up read of a long log that
  finished after a short charge had closed put the older charge back in the idle notification, and
  in each recording notification's `lastRecap`, until the next close. A recap now ranks by the
  order its session closed in, the start-up read below every close, and a read that ranks lower is
  dropped. An idle post whose content was built before a newer recap was kept (a recap's, the
  record switch's, the unplug's) is dropped too, inside the notification's lock, because the newer
  recap's own post follows it. A closed charge whose log cannot be read still shows no recap, never
  an older charge's. `RecorderHost`, the NDJSON schema (2) and the Room projection (6) are
  untouched. Docs: `docs/recording-service-threading.md`, "Which recap the idle notification
  names".
- `RecordingService`'s start-up recap no longer reads the charge in progress. It read the newest log
  in the directory, and on the charger (or after a plug-in that landed before the listing) that
  could be the log the capture pump had just opened: a partial recap of the live charge, which the
  unplug's idle notification then showed as the last charge until the closed charge's own recap
  was read. The start-up recap now leaves out every log the service opened, by its file name,
  recorded before the pump is asked to open it; each of those gets its own recap when it closes.
  `RecorderHost`, the NDJSON schema (2) and the Room projection (6) are untouched. Docs:
  `docs/recording-service-threading.md`, "Which recap the idle notification names".
- `RecordingService`'s idle notification shows the record switch as it stands. An idle post read
  the switch when it built its content and never looked again, so a recap's or the unplug's post
  built just before the switch changed could land after the change's own post and put the old state
  back until the next idle post. The service also followed the switch from a worker thread that
  started listening after its first post, so a change made in between was never posted, and it
  passed on only a new value, so a change and its reversal made off the main thread, both reported
  after the reversal, posted nothing while a post built between them stood. An idle post is now
  dropped, inside the notification's lock, once the switch no longer reads what it shows, and every
  change of the switch, listened for from before the service's first post, launches a post built
  after it, which lands in its place. A host whose `content` throws on one switch post loses that
  post alone, and the next change posts again; in `v0.7.0` such a throw ended the process (below).
  `CapturePrefs.recordEnabledFlow` is unchanged. `RecorderHost`, the NDJSON schema (2) and the
  Room projection (6) are untouched. Docs: `docs/recording-service-threading.md`, "Which switch
  state the idle notification shows".
- A session's row and its samples are written together. They were two writes, row first, so a
  crash or a full disk between them at the end of a recording left a `CLEAN` row over the one
  sample projected while the charge was live, and nothing ever re-read that file: peak, energy,
  phases and the curve came from one sample for good. Now a failed write leaves the row the session
  had (`TRUNCATED`) or none, and the next `reconcile` projects the whole file. Docs:
  `docs/ndjson-format.md`, "Writing the projection".
- `Replay.reconcile` no longer throws a database error into its caller. A host runs it from a UI
  effect at app open and at each recording's end, where a `SQLiteFullException` from a full disk
  ended the process. Any `android.database.SQLException` now ends the pass: it is logged, and what
  was not written waits for the next call. An `Error` still propagates. The `ingest_conflict` and
  `ingest_invalid_id` event lines it writes are best-effort too
  (`docs/traps/event-log-writes-are-best-effort.md`).
- `Replay.ingestExternal` no longer throws a database error into its caller either. One before the
  file lands refuses the import as `IO` and leaves nothing; one after it lands (a full disk) leaves
  the import standing: `Imported` is returned, no row is written, and the next `reconcile` projects
  the file. It used to throw from the host's UI coroutine and end the process.
- `Replay.ingestExternal` stops copying at the first byte past 128 MiB (`TOO_LARGE`) or past a
  64 KiB line (`NO_HEADER`), and leaves nothing in `incoming/`. It used to stage any stream whole,
  then read a file with no newline into memory as one line, so a wrong pick could fill the disk or
  end the process a recording shares. The figures come from two days at one line a second, every
  line as wide as the widest line recorded (a header).
- `Replay.parse` no longer holds a whole file in memory. It decoded every line of a session log and
  then built every row from them, holding both at once, so a file of minimal sample lines near the
  import's 128 MiB cap decoded into millions of each, in the process a recording shares, at app
  open (`reconcile`) and at each recording's end (the recap read). Each line now becomes its row as
  it is read, and a file past `Replay.MAX_SAMPLES` samples is refused (`Replay.TooManySamples`):
  `reconcile` and `projectAll` skip it and leave it on disk, the recap shows nothing for it, and an
  import refuses it as `TOO_LARGE`. The recorder's own log could reach the cap: a charge held short
  of its target while the platform still reported it charging (an OEM charge limit) never settled
  (it does now, above), so it was sampled nearly every second until the unplug, and about four days
  of that crossed it. A log an older build wrote can still be past it. A row
  projected before then keeps what it showed, and the file is noted in the event log and not read
  again in that process while it only grows (one that gets shorter is read again); each app start
  that meets it reads it once more, up to the cap, and notes it again. Host-side only:
  `RecorderHost`, the NDJSON schema (2) and the Room projection (6) are untouched. Docs:
  `docs/ndjson-format.md`, "Writing the projection".
- A reverted entity change no longer leaves its Room schema export behind. Room never deletes an
  export, the room3 plugin copied it back over the restored `schemas/<version>.json` on every
  build, and the build cache kept it past a clean. The recorder's build empties the export before
  KSP runs.
- `RecordingService` no longer crashes its process when a session log is deleted, or cannot be
  read, at the moment its recap is read. That happens at service start and at every session
  close. The notification's recap is simply absent.
- One capture effect that fails no longer skips the rest of its batch, and no longer ends the
  process. An event-log write that failed at unplug (a full disk, a log directory that cannot be
  written) used to skip the session's `session_end`, the log's close and the wakelock's release;
  the next plug-in then threw `IllegalStateException` ("session log already open") on the input
  pump and the process died, to be restarted into the same state. Every sample after a session log
  that could not be created threw the same way ("no open session log"). Each effect now runs on
  its own: an `Exception` is logged and the batch carries on, so a session is always closed and its
  wakelock always released, and the next plug-in opens a new log. An `Error` still ends the
  process. Docs: `docs/recording-service-threading.md`, "When an effect fails".
- A host whose `RecorderHost.content` (or `build`, through the notification) throws on one of
  `RecordingService`'s coroutines (the tick loop, the quiet watch, a record switch post, a recap's
  read) no longer ends the process. The `Exception` is logged and ends that coroutine
  alone: the session still records and still ends, and the quiet watch re-arms a tick loop ended
  this way. An `Error` still ends the process, and so does a host that throws in a post made
  outside any coroutine, on the main thread (the service's first post, a broadcast's post). Docs:
  `docs/recording-service-threading.md`, "When anything else on the scope fails".
- `RawLogWriter.open` is all or nothing: a header that cannot be written now leaves the writer
  closed, rather than open on a file with no header, so the next `open` is not refused.
  `append` with no log open still throws `IllegalStateException`, as before; the service no
  longer lets it escape.

## [0.7.0] — 2026-10-14

The app's 0.7.0 also carries the phone-side fix for the "Receive from watch" crash: a
vendor Bluetooth stack that refuses the listen call with `SecurityException` now ends the window
instead of the process, and the phone declares the legacy `BLUETOOTH` permission. Host-side; the
recorder changes are below. **Room version 6** drops the projection once at the next reconcile.

### Added

- `GaugeProfiles.PHONE_MA` (`gauge-phone-ma`): a phone gauge that reports current in mA. The scale
  probe now runs on phones too, by a stricter two-answer rule (`PhoneScaleEvidence`: the median of
  the first fifteen genuinely charging samples, and a level rise those currents could not carry if
  they were µA); the answer travels in the existing `gauge_scale` event. `GaugeScaleProbe` gains
  `offer(RawLine.Sample)`.
- `SignEvidence`: a session's current-sign convention read from its own samples — the
  time-integrated current must move the same way as the charge counter (or level).
- `Replay.sessionIdsOnDisk(dirs)`: every session id with a log in any `LogDirs` root, from file
  names, under the projection lock — what "gone" means for a host keeping per-session state.
- `Replay.collisionHidden(dirs, dao)` and `Replay.deleteCollisionHidden(dirs, dao, keepId)` — the
  session files no row shows because another device's session holds their id (start in ms), and a
  locked delete of exactly those files. Additive: no host change.

### Fixed

- A phone is no longer assumed charging-positive. `GaugeProfiles.PHONE.chargingPositive` is now
  null, so new phone headers no longer declare a guess, and `sessionFacts` reads a phone session's
  sign from `SignEvidence`, ignoring the header (every phone header ever written said `true`). On
  a phone that reports charging as negative, that guess read the peak far below the true one.
- `Replay.parse` re-judges a `gauge-phone` log with no `gauge_scale` event by the same phone rule,
  so a milliamp phone's old logs stop reading a thousand times low. **Room version 6** changes no
  column; it exists to drop the projection once so every log is re-parsed at the next reconcile.
- `Replay.projectAll` re-projects the session that was showing when two devices' sessions share an
  id, so a rebuild never swaps which one is visible; with neither projected, `LogDirs.all()` order
  decides, as in `reconcile`.
- `BootReceiver` and `PackageReplacedReceiver` no longer crash the process when an OEM battery
  manager refuses the recorder's start despite the documented exemption. The refusal is logged and
  written to `events.ndjson` as the new `start_refused` event (`detail` `action=BOOT_COMPLETED` or
  `action=MY_PACKAGE_REPLACED`); the recorder stays down until the app is opened. Additive: no host
  change, no schema bump.

## [0.6.2] — 2026-10-13

No recorder change. Stamped because every ChargeLog release is a recorder release of the same
number (see Versioning). The app's 0.6.2 supersedes 0.6.1, which was cut and never uploaded: it
carries that fix, the residual kill path behind it — a listen window whose foreground promotion
was refused settled the platform's foreground promise before stopping, instead of being killed for
not keeping it — and a diagnostics bundle that can carry the crash, the permission grants and a
session log over 1 MiB. All of it is host-side: `RecorderHost`, the NDJSON schema (2) and the Room
projection (5) are untouched, and no fixture moves.

## [0.6.1] — 2026-10-10

No recorder change. Stamped because every ChargeLog release is a recorder release of the same
number (see Versioning). The app's 0.6.1 is one fix and its fallout: the phone's Bluetooth listen
window promoted itself to the foreground before it could bail, which is what the platform kills a
service for, and the diagnostics bundle can now carry the crash that prompted it. All of it is
host-side — `RecorderHost`, the NDJSON schema (2) and the Room projection (5) are untouched, and
no fixture moves.

## [0.6.0] — 2026-10-07

### Added

- **`LogDirs.importedRoot`: imported logs can live apart from synced ones.** A new five-argument
  `LogDirs` constructor takes an `importedRoot` (read back through the `importedRoot` property, null
  by default), with `LogDirs.importedDir(deviceId)`, `LogLayout.importedRoot(context)` and
  `LogDirs.of(context, separateImports = true)`, and `LogDirs.importedCopy(session)` naming a
  session's imported file. The existing constructor and `LogDirs.of(context)` keep their exact
  signatures (`@JvmOverloads`), so a host compiled against an earlier release still links. With
  the root set:
  - `Replay.ingestExternal` moves an imported log to `<importedRoot>/<deviceId>/` instead of
    `synced/<deviceId>/` and refuses a session either root already holds with the new
    `IngestReasons.DUPLICATE` (an empty or headerless stub does not count as held). Its checks,
    move and projection now run under the same lock as `reconcile` and the deletes.
  - `all()` lists the imported directories after the synced ones, so `reconcile` and `projectAll`
    project them, and both skip a synced file whose imported twin exists: the import shadows it and
    both stay on disk.
  - `Replay.deleteImportedCopy` and `Replay.deleteSyncedCopy` delete one root's copy by its explicit
    path, under the lock. A reconcile after `deleteImportedCopy` reveals a shadowed synced copy.
    `deleteImportedCopy` returns false and does nothing without the root, and never drops the row
    of a session held only as synced.
  - `deleteSessionCompletely` deletes a session's synced copy before its imported one, stops at
    the first that cannot go, and keeps the rows while a copy remains, so a failure leaves the
    session imported.
  The directory records how a log arrived: an import written into `synced/<deviceId>/` lands on the
  exact path a sync receiver appends to and resumes from. Files already on disk are never moved. A
  host that does not set the root sees no change. No NDJSON, Room or `RecorderHost` change.
- **`LiveFeed.Snapshot.eventSampled`** (default false) says the sampler has settled to battery
  broadcasts, where a long silence means "unchanged"; `LiveFeed.onSampling` sets it from the
  service's `SetSampling` effect and `onOpen` clears it. A host judging freshness from the newest
  sample's age reads it to keep a settled hold FRESH. Additive: the old positional constructor
  still compiles.
- **`EventKinds.INGEST_INVALID_ID`**: a session file whose header names a `deviceId` that is not
  a valid directory name is skipped at projection and logged, so no row ever names a file no path
  can reach (before, such a row made `deleteSessionCompletely` throw).

### Changed

- **The foreground notification is rebuilt when the tick loop goes quiet.** While a session is
  sampling on the tick, `RecordingService` checks about once a minute of awake time whether a tick
  has been recorded; if none has, it calls `RecorderHost.content` again with the last sample it
  recorded, posts the result through the usual dedupe gate, and — if the tick loop is gone — logs
  and starts it again. Before this, a tick loop that stopped while the service lived left the last
  notification standing, as if the recorder were fine, because nothing called `content()` again,
  and nothing restarted it until the next plug event. Nothing changes after a session settles to event sampling,
  where silence means the battery has not changed. No member of `RecorderHost` and no seam type
  changes: a host that computes from its argument, as the `content()` contract requires, is handed
  an input it has seen, returns the same key, and the gate drops it. A host that wants to show a
  quiet recorder compares the sample's `t` with its own clock.
  `docs/recording-service-threading.md` "The quiet re-post" has the reasoning.

- **Foreground posts are serialized, and a service start records its channel.** Two posts racing
  from different threads could leave two ongoing notifications in the shade; they now take turns.
  A service that restarts mid-charge now swaps notification ids on its first recording post, as
  every other idle-to-recording change does, instead of updating its idle notification in place.
  No host-visible API change.
- `Replay.deleteSyncedCopy` returns false and does nothing for this device's own session or an
  invalid id, as `deleteImportedCopy` already did; `LogDirs.importedCopy` is null for an invalid
  id. The import's "already held" check reads a file only until its header instead of parsing
  every sample, so a multi-hour log no longer holds the projection lock for a full parse twice.

## [0.5.0] — 2026-10-02

No recorder change. Stamped because every ChargeLog release is a recorder release of the same
number (see Versioning). The app's 0.5.0 changes — negotiated power judged from the held cap with a
plausibility ceiling, a phone-only "in a case" tag, a withdrawal that runs to the end, and the Fold
pass fixes (efficiency without the Adaptive hold, no zero-length STARTUP span) — live outside the
recorder; `RecorderHost`, the NDJSON schema (2) and the Room projection (5) are untouched.

## [0.4.0] — 2026-09-21

### Changed

- **`SessionFacts.peakW` is the largest power flowing INTO the battery, not the largest magnitude.**
  A plugged-in stretch can run the wrong way — the battery driving the device while the OS still
  reports charging — and such a sample is not a charging speed, so it can no longer set the peak.
  When every sample reads that way the field is now `null` rather than a drain reported as a
  charge. Behaviour is unchanged wherever `chargingPositive` is null: an unmeasured sign convention
  still yields the sign-insensitive magnitude, because `Units.signedWatts` gives no signed number
  rather than a guessed one. `Units.watts` itself is untouched, and every other watt on every
  screen and in every golden stays byte-identical.

  Hosts reading `peakW` should expect `null` on a session that never charged. On the two real
  captures in the corpus this changes nothing — both contain draining samples (12 of 4,045 and 70
  of 7,455) and in neither does one win the peak.

## [0.3.3] — 2026-09-17

Stamped with the ChargeLog app release that first ships it; the library has no version of its
own yet (see Versioning).

### Added

- The session header carries `cycleCount`: `BatteryManager.EXTRA_CYCLE_COUNT` as the sticky
  battery intent reported it at plug-in, nullable and absent when the platform does not supply
  the key. Additive under the tolerant-decode contract, so the NDJSON schema stays 2 and the
  Room projection is unchanged. Nothing host-visible: `RecorderHost` is untouched.

## [0.3.2] — 2026-09-17

Stamped with the ChargeLog app release that first ships it; the library has no version of its
own yet (see Versioning).

### Changed

- `SettleDetector` resumes when the level rises above the level it settled at while the status is
  still `CHARGING`, and from then on only a status (`FULL`, or `NOT_CHARGING` at the target) can
  settle the session. The pinned-level predicate's 120 s hold is shorter than a top-of-charge level
  increment (1.9–5.2 min measured on a Pixel Fold from 94 %), so it was settling in late CV and
  releasing the wake lock ~7 min before the charge ended: the last ~2 % of every charge was
  sampled at ~80 s instead of 1 s. The first pinned settle is unchanged, so a gauge that never
  reports `FULL` still settles; only a settle contradicted by a subsequent rise is withdrawn. The
  `capture_policy` event detail `"resumed"` already existed; no schema change.


First public release in preparation. Everything below describes the state of the code as it is
published, not a change from a previous public version — there isn't one.

### The starting point

- `RecorderHost`: the ten-member seam a host implements. Foreground-service notification
  content and rendering, channel labels, charge-target level, raw battery-state callbacks, the
  app version stamped into session headers, the device kind and gauge profile (both with phone
  defaults, so the seam can grow without breaking an existing host), and the consent answer
  the background start paths ask for (default `true`, see "Fixed" below), and the cell's design
  capacity in mAh (default `null`).

  **Host-visible:** `RecorderHost` gains `fun onGaugeRefined(profile: GaugeProfile) = Unit`,
  called once when the recorder resolves an `UNKNOWN` gauge's current scale from a session's own
  first samples. The recorder persists the answer itself (`CapturePrefs.learnedGaugeId`) and every
  later session it opens uses it, so a host that does nothing here stays correct; the callback
  exists so a host that also *reads* the gauge — live watts, its own analysis — can stop being a
  thousand times wrong before its next process start rather than after it. Additive, with a
  default that describes what the recorder did before the member existed.

  **Host-visible:** `RecorderHost` gains `fun designCapacityMah(): Int? = null`, written into each
  session header. The recorder cannot determine it — the kernel's `charge_full_design` is refused
  to an unprivileged app by SELinux — so a host that knows the figure (a `PowerProfile` read, a
  value the user typed) supplies it, and one that does not returns `null`. Additive, with a default
  that describes what the recorder did before the member existed.
- `RecordingService`: always-on `specialUse` foreground service, started from `BOOT_COMPLETED`,
  1 Hz sampling while plugged, wake lock held only for an unsettled session. Two notification
  ids alternating, never leaving the foreground — see "Notifications" in `BUILDING.md`.
- NDJSON capture log, **schema 2** — one append-only file per session plus a rolling
  `events.ndjson`. Format documented in `docs/ndjson-format.md`.

  The `gauge_scale` event is additive and does **not** bump the schema: an older reader that does
  not know the kind skips it and reads the header, which is what it did before. A reader that does
  know it must prefer it over the header's `gaugeProfileId` — the header is written at plug-in and
  records what was assumed; the event records what the samples measured. See
  `docs/ndjson-format.md`.
- Room projection (**database version 5**) rebuilt from the logs by `data/Replay.kt`, with
  `Replay.reconcile()` on the recording → idle edge.
- `measure/`: `SessionFacts` (duration, level delta, energy, peak power, peak temperature, charge
  source, and the gauge's counter kind), `Units` raw-unit conversion, `AnalyzerSample`, CSV export.

  `Units.levelPct` folds a sample's `scale` into its `level` at ingest — in `Replay` and in the
  live `RawLine.Sample -> AnalyzerSample` adapter — so a level reaches the projection and the
  analyzer as a percent whatever maximum the gauge reported it against. Every gauge observed so
  far says 100 (42,607 of 42,607 samples) and is unchanged by this; the log keeps both fields raw.

  **Host-visible:** `SessionFacts` carries `counterKind`, defaulted to `null`, so a consumer can
  tell whether `energyAh` was integrated by a real coulomb counter or computed from the level. It
  matters: on a `SOC_DERIVED` gauge the counter *is* capacity x level, so inferring capacity from
  `energyAh / levelGain` cancels to the gauge's declared constant rather than measuring the cell.
  Additive — a host that constructs `SessionFacts` positionally still compiles. When the header
  declared no kind (schema-1 files), it is resolved from the gauge catalog for the session's
  profile, the same way the current scale is; null only when neither knows.

  **Host-visible:** `SessionFacts` also carries `currentScale`, defaulted to
  `CurrentScale.MICRO_AMP`, so a consumer holding the facts never has to re-derive the gauge's
  units from the profile id to read a watt. Additive — a host that constructs `SessionFacts`
  positionally still compiles — and no schema change: the scale was always resolvable from
  `gaugeProfileId`, this only stops every consumer resolving it again. It exists because the two
  that did not both read a milliamp gauge 1000x low.
- Fuel-gauge quirk profiles — declarative only, per `CONTRIBUTING.md`'s hard rule.
- `:sample`: a minimal Compose host app that implements `RecorderHost` end to end.
- Real-session NDJSON fixtures and device evidence from the S0–S3 spikes, CC0-1.0.
- Licensing: GPL-3.0-only, REUSE 3.3 compliant, contributor CLA for code (not for evidence).

### Added

- **The platform's charging attribution, per sample.** `RawLine.Sample.chargingStatus` records
  `BatteryManager.EXTRA_CHARGING_STATUS` raw (1 normal · 2 too cold · 3 too hot · 4 long life ·
  5 adaptive — constants in `measure/ChargingStatus`), a `charging_status` event marks each
  change, and the header's capability block declares `reportsChargingStatus`. This is the field
  that lets a reader tell a slow charger from a phone that chose to charge slowly. Nullable and
  additive: the NDJSON schema stays **2**; the Room projection is now **version 5** (destructive,
  as always — the logs are the source of truth). The CSV export gains a last `charging_status`
  column.

  **Host-visible:** `RawLine.Sample`, `Capabilities`, `SampleEntity`, `SessionEntity` and
  `AnalyzerSample` each gain a defaulted property; `SamplerProfiles.forHost` gains a defaulted
  `reportsChargingStatus` parameter. A host that constructs any of them still compiles.
- **A measured current-sign convention per gauge.** `GaugeProfile.chargingPositive` (default
  `null`) says whether a positive raw current is charge flowing into the battery: `true` on the
  phone gauge and the Pixel Watch gauge, both measured; `null` on the Samsung and unknown
  profiles. The header declares it per session as `Capabilities.chargingPositive`, and
  `SessionFacts.chargingPositive` resolves it declared-else-catalog, exactly as `counterKind` is.
  `Units.signedWatts` reads it — positive into the battery, negative out, `null` on an unmeasured
  gauge rather than a guessed sign — and `Units.watts` is unchanged.

### Fixed

- **The boot and update starts now ask the host for consent.** `BootReceiver` and
  `PackageReplacedReceiver` started `RecordingService` unconditionally, so a host's own consent
  gate — a first-run flow its launch activity waits for — held on launch and leaked on the next
  reboot or store update: a user who had opened the app once and backed out of the first page
  got a permanent foreground service and recorded sessions before seeing what would be recorded.
  Both receivers now return without starting (one warning line in logcat) when the host says no,
  and write no event line either, so an unconsented device gets no log directory on its behalf.

  **Host-visible:** `RecorderHost` gains `fun captureConsented(): Boolean = true`. Additive — an
  existing host compiles unchanged and keeps starting on every boot and update, exactly as
  before. A host with a consent step overrides it with the same read its launch gate makes.

- **A channel transition no longer leaves the foreground.** `RecordingService` used to remove its
  notification with `stopForeground(STOP_FOREGROUND_REMOVE)` and post a fresh one on every
  recording ↔ idle transition, because an `OngoingActivity` chip only clears on a genuine
  replacement. Leaving the foreground drops the service's foreground-start grant, so the
  `startForeground` right after it is re-checked against Android 12+'s background-start
  restriction and throws `ForegroundServiceStartNotAllowedException` whenever the app is
  backgrounded — which is the normal case at the end of a charge, and unavoidable on Wear, where
  no host can hold a battery-optimization exemption. The service died on the main thread while
  closing the session it was recording, truncating it, and then crash-looped on restart.

  The replacement is now done by calling `startForeground` with a *second* notification id while
  still foreground and cancelling the old one — no background-start check, and the same visible
  removal. `NotificationIds` owns the pair (`PRIMARY` **1**, `ALTERNATE` **8**) and the pure
  `next(current, channelChanged)` alternation between them.

  **Host-visible:** the recorder now reserves **two** notification ids rather than one, and the
  id a post lands on alternates. `HostContent` carries a new `notificationId` field, set by the
  recorder: a host that binds anything to the id — a Wear `OngoingActivity`, say — must read it
  from the content rather than assume `RecordingService.NOTIFICATION_ID`. A host must not post,
  update or cancel either reserved id itself; the collision is silent — `ALTERNATE` was 2 before
  release, which is ChargeLog's own low-battery reminder, and the reminder simply stopped firing.

- **A session's charge source is read from the first sample that reports a plug, not from the
  first sample.** `sessionFacts` labelled a session UNKNOWN whenever its first sample said
  `plugged=0`, which happens when the sticky `BATTERY_CHANGED` the recorder samples predates the
  plug — seen on 4 of 14 real phone sessions. Existing logs are re-read at display time, so the
  label corrects itself with no data change.

- **A session's first sample now carries the plug it is about.** `RecordingService` sampled the
  sticky `BATTERY_CHANGED` it had last received, which can predate `POWER_CONNECTED` by seconds
  or minutes; the session's first sample then said unplugged and discharging, with a voltage and
  level that old. The service now re-reads the system's sticky at the plug edge, as it already
  did at start. No format change: `voltageAgeMs` still measures the intent-side values' age
  against the property read.
