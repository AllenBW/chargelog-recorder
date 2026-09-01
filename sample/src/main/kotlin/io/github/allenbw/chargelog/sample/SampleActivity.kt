// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.sample

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.allenbw.chargelog.capture.LiveFeed
import io.github.allenbw.chargelog.capture.RecordingService
import io.github.allenbw.chargelog.data.Replay
import io.github.allenbw.chargelog.data.SessionEntity
import io.github.allenbw.chargelog.measure.ExportCsv
import io.github.allenbw.chargelog.measure.Units
import io.github.allenbw.chargelog.measure.chargeSourceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date

/** Staged share copies, the only thing the FileProvider in the manifest exposes. */
private const val SHARE_DIR = "share"

/** How long a staged copy is left on disk: long enough that a share still being read has its
 *  file, short enough that exported sessions do not pile up. */
private const val SHARE_TTL_MS = 60 * 60 * 1000L

class SampleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SampleApp
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { SampleScreen(app) }
            }
        }
    }
}

@Composable
private fun SampleScreen(app: SampleApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val live by LiveFeed.state.collectAsStateWithLifecycle()
    val sessions by remember { app.db.dao().sessions() }.collectAsStateWithLifecycle(emptyList())
    val askNotifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // Startup: ask for the permission the notification needs, get the recorder running, and catch
    // the projection up with whatever was written while this UI was not alive.
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        RecordingService.start(context)
        Replay.reconcile(app.logDirs, app.db.dao())
    }

    // The one thing about this seam that is not guessable: the recorder writes log FILES, and
    // nothing but Replay ever writes a database row. So the list above would sit empty from the
    // plug-in until the next cold start. Reconciling on each recording-ended edge is what makes a
    // finished session appear while the user is still looking at the screen.
    LaunchedEffect(Unit) {
        var wasRecording = false
        LiveFeed.state.collect { snapshot ->
            val recording = snapshot?.recording == true
            if (wasRecording && !recording) Replay.reconcile(app.logDirs, app.db.dao())
            wasRecording = recording
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(liveLine(context, live), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.sessions), style = MaterialTheme.typography.titleSmall)
        if (sessions.isEmpty()) Text(stringResource(R.string.no_sessions))
        LazyColumn {
            items(sessions, key = { it.id }) { session ->
                SessionRow(session) { csv -> scope.launch { share(context, app, session, csv) } }
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionEntity, onShare: (csv: Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(sessionLine(LocalContext.current, session))
        Row {
            TextButton(onClick = { onShare(true) }) { Text(stringResource(R.string.share_csv)) }
            TextButton(onClick = { onShare(false) }) { Text(stringResource(R.string.share_raw)) }
        }
    }
}

/**
 * Watts, level and charge source straight off the recorder's live snapshot. `recording` and
 * `sample` are two separate questions: `LiveFeed.onOpen` publishes a recording snapshot with no
 * sample yet and holds it until the first tick lands. Collapsing that state into the idle branch
 * would put "Idle" on screen while the notification correctly said "Recording".
 */
private fun liveLine(context: Context, snapshot: LiveFeed.Snapshot?): String {
    if (snapshot?.recording != true) return context.getString(R.string.live_idle)
    val sample = snapshot.sample ?: return context.getString(R.string.live_recording_pending)
    val unknown = context.getString(R.string.unknown)
    return context.getString(
        R.string.live_recording,
        Units.watts(sample.currentRaw, sample.voltageRaw)?.let(::format1) ?: unknown,
        sample.level?.toString() ?: unknown,
        chargeSourceOf(sample.plugged).name,
    )
}

private fun sessionLine(context: Context, session: SessionEntity): String {
    val unknown = context.getString(R.string.unknown)
    val started = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(session.startedAtMs))
    val duration = session.endedAtMs
        ?.let { context.getString(R.string.minutes, (it - session.startedAtMs) / 60_000) }
        ?: unknown
    return context.getString(
        R.string.session_line,
        started,
        duration,
        session.startLevel?.toString() ?: unknown,
        session.endLevel?.toString() ?: unknown,
    )
}

/**
 * Both shares go out as a staged copy under `cacheDir/share/`. The CSV has to be materialised
 * anyway, and the raw log cannot be served in place: sessions live under `noBackupFilesDir`
 * ([io.github.allenbw.chargelog.data.LogLayout]), which FileProvider has no path element for —
 * only the catch-all `root-path` reaches it, and that would expose the whole app sandbox. One
 * staging directory, swept by age, is the scoped alternative.
 *
 * Sharing a session that is still recording copies a log the writer still has open, so the last
 * line of the NDJSON may be cut mid-write — the same snapshot a reader gets from any live log,
 * which is why `Replay` classifies logs without a terminal event as TRUNCATED.
 */
private suspend fun share(context: Context, app: SampleApp, session: SessionEntity, csv: Boolean) {
    val appName = context.getString(R.string.app_name)
    val staged = try {
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
            // A staged copy is a complete export of a session, so the directory has to be swept —
            // but by age, never wholesale. The grant handed to the last share outlives the chooser:
            // a target that uploads in the background reads the file after the user has moved on,
            // and clearing the directory on the next share would pull it out from under that read.
            val staleBefore = System.currentTimeMillis() - SHARE_TTL_MS
            dir.listFiles()?.forEach { if (it.lastModified() < staleBefore) it.delete() }
            val out = File(dir,
                if (csv) ExportCsv.suggestedCsvName(session, "session")
                else ExportCsv.suggestedRawName(session))
            if (csv) {
                out.writeText(ExportCsv.csv(session, app.db.dao().samples(session.id), appName))
            } else {
                val source = File(app.logDirs.dirFor(session), session.sourceFile)
                // The raw log is the source of truth and can be deleted out from under a row.
                if (!source.exists()) return@withContext null
                source.copyTo(out, overwrite = true)
            }
            out
        } ?: return context.toast(R.string.share_missing)
    } catch (_: IOException) {
        return context.toast(R.string.share_failed)
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", staged)
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType(if (csv) "text/csv" else "application/x-ndjson")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            context.getString(R.string.share_chooser),
        ),
    )
}

private fun Context.toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
