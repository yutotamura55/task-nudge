package dev.tasknudge.data

import android.app.Application
import dev.tasknudge.core.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupCodecTest {
    private val codec = BackupCodec()
    private val exportedAt = Instant.parse("2026-10-28T03:00:00Z")
    private val backup = Backup(
        ReminderSettings(8, 22, 15),
        listOf(
            BackupTask("首の \"ストレッチ\" \\ 1/2", 3, null, Instant.parse("2026-09-30T00:00:00.123Z"), false, null, listOf(
                BackupCompletion(Instant.parse("2026-10-01T01:00:00Z"), LocalDate.of(2026, 10, 1)),
                BackupCompletion(Instant.parse("2026-10-04T23:30:00Z"), LocalDate.of(2026, 10, 5)),
            )),
            BackupTask(
                "水を飲む 💧", 1, 2, Instant.parse("2026-09-30T00:00:00.123Z"),
                true, Instant.parse("2026-10-29T00:00:00Z"), emptyList(),
            ),
        ),
    )

    private fun text(value: Backup = backup) =
        ByteArrayOutputStream().also { codec.write(value, exportedAt, it) }.toString(Charsets.UTF_8.name())
    private fun read(text: String) = codec.read(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
    private fun problem(bytes: ByteArray, reader: BackupCodec = codec) =
        (runCatching { reader.read(ByteArrayInputStream(bytes)) }.exceptionOrNull() as InvalidBackupException).problem
    private fun problem(text: String) = problem(text.toByteArray(Charsets.UTF_8))

    @Test fun readsBackWhatItWrites() {
        assertEquals(backup, read(text()))
        assertEquals(Backup(ReminderSettings(), emptyList()), read(text(Backup(ReminderSettings(), emptyList()))))
    }

    @Test fun writesReadableJsonStartingWithTheFormat() {
        val written = text()
        val header = listOf(
            "{",
            "  \"format\": \"task-nudge-backup\",",
            "  \"version\": 1,",
            "  \"exportedAt\": \"2026-10-28T03:00:00Z\",",
        ).joinToString("\n")
        assertTrue(written, written.startsWith(header))
        assertTrue(written.contains("\"title\": \"水を飲む 💧\""))
    }

    @Test fun identifiesFilesThatAreNotBackupsOrAreNewer() {
        assertEquals(BackupProblem.NOT_A_BACKUP, problem("{\"format\": \"other\", \"version\": 1}"))
        assertEquals(BackupProblem.NOT_A_BACKUP, problem("{\"version\": 1, \"format\": \"task-nudge-backup\"}"))
        // A newer file may hold fields this version does not know.
        val newer = text().replace("\"version\": 1", "\"version\": 2").replace("\"tasks\"", "\"future\"")
        assertEquals(BackupProblem.NEWER_VERSION, problem(newer))
        assertEquals(BackupProblem.MALFORMED, problem(text().replace("\"version\": 1", "\"version\": 0")))
    }

    @Test fun rejectsAnythingTheWriterWouldNotProduce() {
        val written = text()
        for (invalid in listOf(
            "",
            "[]",
            written.dropLast(2),
            "$written {}",
            written.replace("\"archived\": false", "\"archived\": false, \"archived\": true"),
            written.replace("\"archived\": false", "\"archived\": false, \"extra\": 1"),
            written.replace("\"archived\": false,", ""),
            written.replace("\"recurrenceDays\": 3", "\"recurrenceDays\": \"3\""),
            written.replace("\"recurrenceDays\": 3", "\"recurrenceDays\": 3.5"),
            written.replace("\"recurrenceDays\": 3", "\"recurrenceDays\": 99999999999"),
            written.replace("\"title\": \"水", "\"title\": [\"水\"], \"x\": \"水"),
            written.replace("\"archived\": true", "\"archived\": \"true\""),
            written.replace("  \"tasks\"", "  // comment\n  \"tasks\""),
        )) assertEquals(invalid, BackupProblem.MALFORMED, problem(invalid))
    }

    @Test fun reportsInvalidValuesWithTheTaskNumber() {
        val written = text()
        val error = { invalid: String -> runCatching { read(invalid) }.exceptionOrNull() as InvalidBackupException }
        with(error(written.replace("\"2026-10-04T23:30:00Z\"", "\"2026-10-04 23:30\""))) {
            assertEquals(BackupProblem.DATE_TIME, problem); assertEquals(1, taskNumber)
        }
        with(error(written.replace("\"2026-10-05\"", "\"2026-02-30\""))) {
            assertEquals(BackupProblem.DATE_TIME, problem); assertEquals(1, taskNumber)
        }
        with(error(written.replace("\"recurrenceHours\": 2", "\"recurrenceHours\": 25"))) {
            assertEquals(BackupProblem.RECURRENCE, problem); assertEquals(2, taskNumber)
        }
        val interval = written.replace("\"intervalMinutes\": 15", "\"intervalMinutes\": 7")
        assertEquals(BackupProblem.SETTINGS, error(interval).problem)
        val exportedAt = written.replace("\"exportedAt\": \"2026-10-28T03:00:00Z\"", "\"exportedAt\": \"yesterday\"")
        assertEquals(BackupProblem.DATE_TIME, error(exportedAt).problem)
    }

    @Test fun rejectsInvalidUtf8() {
        val bytes = text().toByteArray(Charsets.UTF_8)
        val index = text().indexOf("首")
        val broken = bytes.copyOf().also { it[index] = 0xFF.toByte() }
        assertEquals(BackupProblem.MALFORMED, problem(broken))
    }

    @Test fun stopsReadingAtTheSizeLimitWithoutTrustingTheReportedSize() {
        val written = text().toByteArray(Charsets.UTF_8)
        assertEquals(backup, BackupCodec(written.size.toLong()).read(ByteArrayInputStream(written)))
        assertEquals(BackupProblem.TOO_LARGE, problem(written, BackupCodec(written.size - 1L)))
        // A file that never ends is cut off at the limit.
        val endless = object : java.io.InputStream() { override fun read() = ' '.code }
        val error = runCatching { BackupCodec(1024).read(endless) }.exceptionOrNull() as InvalidBackupException
        assertEquals(BackupProblem.TOO_LARGE, error.problem)
    }

    @Test fun refusesToWriteAFileItCouldNotReadBack() {
        val size = text().toByteArray(Charsets.UTF_8).size
        assertTrue(runCatching { BackupCodec(size - 1L).write(backup, exportedAt, ByteArrayOutputStream()) }.isFailure)
    }
}
