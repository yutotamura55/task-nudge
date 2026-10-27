package dev.tasknudge.data

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import android.util.MalformedJsonException
import dev.tasknudge.core.*
import java.io.EOFException
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Reads and writes the backup file: UTF-8 JSON whose first two keys name the format
 * and its version. Reading treats the file as untrusted, because any app can edit a
 * file outside this one: it stops after [maxBytes], rejects anything the writer would
 * not produce, and validates the result before returning it.
 */
class BackupCodec(private val maxBytes: Long = MAX_BYTES) {
    /**
     * Refuses with [InvalidBackupException] data that [read] would refuse, such as too
     * many tasks, so an export never reports success for a file that cannot be restored.
     */
    fun write(backup: Backup, exportedAt: Instant, output: OutputStream) {
        validateBackup(backup)
        val writer = JsonWriter(OutputStreamWriter(LimitedOutputStream(output, maxBytes), Charsets.UTF_8))
        writer.setIndent("  ")
        writer.beginObject()
        writer.name(FORMAT).value(FORMAT_NAME)
        writer.name(VERSION).value(CURRENT_VERSION.toLong())
        writer.name(EXPORTED_AT).value(exportedAt.toString())
        writer.name(SETTINGS).beginObject()
            .name(START_HOUR).value(backup.settings.startHour.toLong())
            .name(END_HOUR).value(backup.settings.endHour.toLong())
            .name(INTERVAL_MINUTES).value(backup.settings.intervalMinutes.toLong())
            .endObject()
        writer.name(TASKS).beginArray()
        for (task in backup.tasks) {
            writer.beginObject()
            writer.name(TITLE).value(task.title)
            writer.name(RECURRENCE_DAYS).value(task.recurrenceDays.toLong())
            writer.name(RECURRENCE_HOURS).run { task.recurrenceHours?.let { value(it.toLong()) } ?: nullValue() }
            writer.name(CREATED_AT).value(task.createdAt.toString())
            writer.name(ARCHIVED).value(task.archived)
            writer.name(SNOOZED_UNTIL).value(task.snoozedUntil?.toString())
            writer.name(COMPLETIONS).beginArray()
            for (completion in task.completions) {
                writer.beginObject()
                    .name(COMPLETED_AT).value(completion.completedAt.toString())
                    .name(LOCAL_DATE).value(completion.localDate.toString())
                    .endObject()
            }
            writer.endArray()
            writer.endObject()
        }
        writer.endArray()
        writer.endObject()
        writer.flush()
    }

    /** Throws [InvalidBackupException] for every file it does not accept, including one that cannot be read. */
    fun read(input: InputStream): Backup {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val reader = StrictJsonReader(JsonReader(InputStreamReader(LimitedInputStream(input, maxBytes), decoder)))
        val backup = runCatching { reader.readBackup().also { reader.endDocument() } }
            .getOrElse { throw it.asInvalidBackup() }
        return validateBackup(backup)
    }

    private fun StrictJsonReader.readBackup(): Backup {
        if (readFormat() != FORMAT_NAME) throw InvalidBackupException(BackupProblem.NOT_A_BACKUP)
        if (nextName() != VERSION) malformed()
        val version = readInt()
        if (version > CURRENT_VERSION) throw InvalidBackupException(BackupProblem.NEWER_VERSION)
        if (version != CURRENT_VERSION) malformed()
        var settings: ReminderSettings? = null
        var tasks: List<BackupTask>? = null
        readObject(setOf(EXPORTED_AT, SETTINGS, TASKS), begun = true) { name ->
            when (name) {
                EXPORTED_AT -> readInstant()
                SETTINGS -> settings = readSettings()
                TASKS -> tasks = readTasks()
            }
        }
        return Backup(requireNotNull(settings), requireNotNull(tasks))
    }

    /**
     * Returns null for any other file, JSON or not and including an empty one, so it is not
     * reported as a malformed backup. Read and encoding failures, including the size limit,
     * are about the bytes rather than the format and propagate.
     */
    private fun StrictJsonReader.readFormat(): String? = runCatching {
        beginObject()
        if (nextName() == FORMAT) readString() else null
    }.onFailure { failure ->
        val notJson = failure is MalformedJsonException || failure is EOFException
        if (failure is IOException && !notJson) throw failure
    }.getOrNull()

    private fun StrictJsonReader.readSettings(): ReminderSettings {
        var start = 0
        var end = 0
        var interval = 0
        readObject(setOf(START_HOUR, END_HOUR, INTERVAL_MINUTES)) { name ->
            when (name) {
                START_HOUR -> start = readInt()
                END_HOUR -> end = readInt()
                INTERVAL_MINUTES -> interval = readInt()
            }
        }
        return ReminderSettings(start, end, interval)
    }

    private fun StrictJsonReader.readTasks(): List<BackupTask> {
        val tasks = mutableListOf<BackupTask>()
        readArray {
            if (tasks.size == MAX_BACKUP_TASKS) throw InvalidBackupException(BackupProblem.TOO_MANY_TASKS)
            val number = tasks.size + 1
            tasks += try { readTask() } catch (invalid: DateTimeParseException) {
                throw InvalidBackupException(BackupProblem.DATE_TIME, number, invalid)
            }
        }
        return tasks
    }

    private fun StrictJsonReader.readTask(): BackupTask {
        var task = BackupTask("", 0, null, Instant.EPOCH, false, null, emptyList())
        val names = setOf(TITLE, RECURRENCE_DAYS, RECURRENCE_HOURS, CREATED_AT, ARCHIVED, SNOOZED_UNTIL, COMPLETIONS)
        readObject(names) { name ->
            task = when (name) {
                TITLE -> task.copy(title = readString())
                RECURRENCE_DAYS -> task.copy(recurrenceDays = readInt())
                RECURRENCE_HOURS -> task.copy(recurrenceHours = readNullable { readInt() })
                CREATED_AT -> task.copy(createdAt = readInstant())
                ARCHIVED -> task.copy(archived = readBoolean())
                SNOOZED_UNTIL -> task.copy(snoozedUntil = readNullable { readInstant() })
                else -> task.copy(completions = readCompletions())
            }
        }
        return task
    }

    private fun StrictJsonReader.readCompletions(): List<BackupCompletion> {
        val completions = mutableListOf<BackupCompletion>()
        readArray {
            var completedAt = Instant.EPOCH
            var localDate = LocalDate.MIN
            readObject(setOf(COMPLETED_AT, LOCAL_DATE)) { name ->
                when (name) {
                    COMPLETED_AT -> completedAt = readInstant()
                    LOCAL_DATE -> localDate = LocalDate.parse(readString())
                }
            }
            completions += BackupCompletion(completedAt, localDate)
        }
        return completions
    }

    private fun StrictJsonReader.readInstant(): Instant = Instant.parse(readString())

    /** Malformed JSON, UTF-8 and failing providers all mean the file cannot be used. */
    private fun Throwable.asInvalidBackup(): Throwable = when (this) {
        is InvalidBackupException -> this
        is TooLargeException -> InvalidBackupException(BackupProblem.TOO_LARGE, cause = this)
        is DateTimeParseException -> InvalidBackupException(BackupProblem.DATE_TIME, cause = this)
        is IOException, is IllegalStateException, is NumberFormatException ->
            InvalidBackupException(BackupProblem.MALFORMED, cause = this)
        else -> this
    }

    private class TooLargeException : IOException()

    /** Counts what is actually read instead of trusting the size a provider reports. */
    private class LimitedInputStream(input: InputStream, private val maxBytes: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) add(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) add(it.toLong()) }
        override fun skip(n: Long): Long = super.skip(n).also { add(it) }
        private fun add(bytes: Long) {
            count += bytes
            if (count > maxBytes) throw TooLargeException()
        }
    }

    /** Stops an export the import would refuse. */
    private class LimitedOutputStream(output: OutputStream, private val maxBytes: Long) : FilterOutputStream(output) {
        private var count = 0L
        override fun write(b: Int) {
            add(1)
            out.write(b)
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            add(len.toLong())
            out.write(b, off, len)
        }
        private fun add(bytes: Long) {
            count += bytes
            if (count > maxBytes) throw TooLargeException()
        }
    }

    companion object {
        const val MAX_MEGABYTES = 16
        const val MAX_BYTES = MAX_MEGABYTES * 1024L * 1024L
        const val CURRENT_VERSION = 1
        const val FORMAT_NAME = "task-nudge-backup"
        const val MIME_TYPE = "application/json"
        private const val FORMAT = "format"
        private const val VERSION = "version"
        private const val EXPORTED_AT = "exportedAt"
        private const val SETTINGS = "settings"
        private const val START_HOUR = "startHour"
        private const val END_HOUR = "endHour"
        private const val INTERVAL_MINUTES = "intervalMinutes"
        private const val TASKS = "tasks"
        private const val TITLE = "title"
        private const val RECURRENCE_DAYS = "recurrenceDays"
        private const val RECURRENCE_HOURS = "recurrenceHours"
        private const val CREATED_AT = "createdAt"
        private const val ARCHIVED = "archived"
        private const val SNOOZED_UNTIL = "snoozedUntil"
        private const val COMPLETIONS = "completions"
        private const val COMPLETED_AT = "completedAt"
        private const val LOCAL_DATE = "localDate"
    }
}

/**
 * Accepts only the JSON types and keys the caller names. [JsonReader] alone converts
 * between strings and numbers and allows repeated or missing keys.
 */
private class StrictJsonReader(private val reader: JsonReader) {
    fun beginObject() {
        expect(JsonToken.BEGIN_OBJECT)
        reader.beginObject()
    }

    /** The next key of the current object, or null at its end. */
    fun nextName(): String? = if (reader.hasNext()) reader.nextName() else null

    /**
     * Reads an object whose keys are exactly [names], each once, in any order.
     * With [begun], the caller has already read the opening brace and some fields.
     */
    fun readObject(names: Set<String>, begun: Boolean = false, field: (String) -> Unit) {
        if (!begun) beginObject()
        val seen = mutableSetOf<String>()
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (name !in names || !seen.add(name)) malformed()
            field(name)
        }
        reader.endObject()
        if (seen != names) malformed()
    }

    fun readArray(item: () -> Unit) {
        expect(JsonToken.BEGIN_ARRAY)
        reader.beginArray()
        while (reader.hasNext()) item()
        reader.endArray()
    }

    fun <T> readNullable(read: () -> T): T? =
        if (reader.peek() == JsonToken.NULL) null.also { reader.nextNull() } else read()

    fun readString(): String {
        expect(JsonToken.STRING)
        return reader.nextString()
    }

    fun readInt(): Int {
        expect(JsonToken.NUMBER)
        return reader.nextInt()
    }

    fun readBoolean(): Boolean {
        expect(JsonToken.BOOLEAN)
        return reader.nextBoolean()
    }

    /** Rejects anything after the top-level value. */
    fun endDocument() = expect(JsonToken.END_DOCUMENT)

    private fun expect(token: JsonToken) { if (reader.peek() != token) malformed() }
}

private fun malformed(): Nothing = throw InvalidBackupException(BackupProblem.MALFORMED)
