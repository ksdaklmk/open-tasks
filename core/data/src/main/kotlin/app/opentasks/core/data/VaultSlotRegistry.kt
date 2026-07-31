package app.opentasks.core.data

import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * An opaque local storage slot for one vault.
 *
 * The legacy slot keeps the storage names this application shipped with. Every
 * other slot is a random name whose only derived form is a SHA-256 digest, so a
 * slot never reveals a vault, an account, or a recovery operation.
 */
class VaultSlot private constructor(val value: String) {
    internal val digest: String
        get() = hexDigest(value.toByteArray(Charsets.UTF_8))

    override fun equals(other: Any?): Boolean = other is VaultSlot && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "VaultSlot([redacted])"

    companion object {
        val LEGACY = VaultSlot(LEGACY_VALUE)

        fun new(): VaultSlot {
            val random = ByteArray(STAGED_NAME_BYTES).also(SECURE_RANDOM::nextBytes)
            return VaultSlot(STAGED_PREFIX + hex(random))
        }

        fun parse(value: String): VaultSlot {
            require(isStorable(value)) { "The vault slot name is not storable" }
            return if (value == LEGACY_VALUE) LEGACY else VaultSlot(value)
        }

        internal fun parseOrNull(value: String): VaultSlot? =
            if (isStorable(value)) parse(value) else null

        private fun isStorable(value: String): Boolean {
            if (value == LEGACY_VALUE) return true
            if (value.length != STAGED_PREFIX.length + STAGED_NAME_BYTES * 2) return false
            if (!value.startsWith(STAGED_PREFIX)) return false
            return value.drop(STAGED_PREFIX.length).all { it in '0'..'9' || it in 'a'..'f' }
        }

        private const val LEGACY_VALUE = "legacy"
        private const val STAGED_PREFIX = "s"
        private const val STAGED_NAME_BYTES = 16
        private val SECURE_RANDOM = SecureRandom()
    }
}

internal fun hexDigest(bytes: ByteArray): String =
    hex(MessageDigest.getInstance("SHA-256").digest(bytes))

private fun hex(bytes: ByteArray): String =
    CharArray(bytes.size * 2).also { encoded ->
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            encoded[index * 2] = HEX_DIGITS[value ushr 4]
            encoded[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
        }
    }.concatToString()

private const val HEX_DIGITS = "0123456789abcdef"

/**
 * The durability boundary every vault registry writes through.
 *
 * Staging and committing are separate so an activation can synchronise a
 * temporary marker before the atomic replacement that publishes it.
 */
interface VaultRegistryFileOperations {
    fun readBytes(file: File): ByteArray?

    fun stageWrite(file: File, bytes: ByteArray)

    fun commitWrite(file: File)

    fun discardWrite(file: File)

    fun delete(file: File)

    fun ensureDirectory(directory: File)
}

class AtomicFileVaultRegistryOperations : VaultRegistryFileOperations {
    private val pending = ConcurrentHashMap<String, PendingWrite>()

    override fun readBytes(file: File): ByteArray? {
        // A staged replacement stays invisible: reading through AtomicFile would
        // discard the synchronised temporary file it is about to publish.
        if (pending.containsKey(file.path)) {
            return if (file.isFile) file.readBytes() else null
        }
        val legacyBackup = File(file.parentFile, "${file.name}.bak")
        if (!file.isFile && !legacyBackup.isFile) return null
        return AtomicFile(file).readFully()
    }

    override fun stageWrite(file: File, bytes: ByteArray) {
        discardWrite(file)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
        pending[file.path] = PendingWrite(atomic, stream)
    }

    override fun commitWrite(file: File) {
        val write = checkNotNull(pending.remove(file.path)) {
            "No staged registry write is pending"
        }
        write.atomic.finishWrite(write.stream)
        syncDirectory(file.parentFile)
    }

    override fun discardWrite(file: File) {
        pending.remove(file.path)?.let { write -> write.atomic.failWrite(write.stream) }
    }

    override fun delete(file: File) {
        discardWrite(file)
        AtomicFile(file).delete()
        syncDirectory(file.parentFile)
    }

    override fun ensureDirectory(directory: File) {
        if (!directory.isDirectory) {
            check(directory.mkdirs() || directory.isDirectory) {
                "The vault registry directory is unavailable"
            }
            syncDirectory(directory.parentFile)
        }
    }

    private fun syncDirectory(directory: File?) {
        val path = directory?.path ?: return
        val descriptor = Os.open(path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private class PendingWrite(
        val atomic: AtomicFile,
        val stream: FileOutputStream,
    )
}

/**
 * The active-slot marker.
 *
 * The marker stores nothing but a format version and an opaque slot, so it can
 * stay readable without any key material and can never lose the active vault to
 * a lost registry key.
 */
class VaultSlotRegistry(
    private val directory: File,
    private val fileOperations: VaultRegistryFileOperations,
) {
    private val marker = File(directory, MARKER_NAME)
    private var directoryPrepared = false

    fun read(): VaultSlot? {
        val bytes = fileOperations.readBytes(marker) ?: return null
        return try {
            require(bytes.size <= REGISTRY_MAX_BYTES) { "bounded" }
            val fields = CanonicalJson.decode(bytes)
            require(fields.keys == MARKER_FIELDS) { "fields" }
            require(fields[FORMAT_VERSION] == MARKER_FORMAT_VERSION) { "version" }
            VaultSlot.parse(fields[SLOT] as? String ?: throw IllegalArgumentException("slot"))
        } catch (_: Throwable) {
            throw IllegalStateException("The vault slot registry is unreadable")
        }
    }

    fun replace(slot: VaultSlot) {
        stageReplacement(slot)
        commitReplacement()
    }

    fun stageReplacement(slot: VaultSlot) {
        if (!directoryPrepared) {
            fileOperations.ensureDirectory(directory)
            directoryPrepared = true
        }
        val bytes = CanonicalJson.encode(
            mapOf(
                FORMAT_VERSION to MARKER_FORMAT_VERSION,
                SLOT to slot.value,
            ),
        )
        check(bytes.size <= REGISTRY_MAX_BYTES) { "The vault slot registry is unwritable" }
        fileOperations.stageWrite(marker, bytes)
    }

    fun commitReplacement() {
        fileOperations.commitWrite(marker)
    }

    fun discardReplacement() {
        fileOperations.discardWrite(marker)
    }

    private companion object {
        const val MARKER_NAME = "active_slot.json"
        const val FORMAT_VERSION = "formatVersion"
        const val SLOT = "slot"
        const val MARKER_FORMAT_VERSION = 1L
        val MARKER_FIELDS = setOf(FORMAT_VERSION, SLOT)
    }
}

internal const val REGISTRY_MAX_BYTES = 65_536

/**
 * A deliberately small strict canonical JSON codec.
 *
 * Only flat objects of text, integers, booleans, and absent values are
 * representable, keys are stored in ascending order, and any deviation from the
 * single canonical encoding is rejected instead of repaired.
 */
internal object CanonicalJson {
    fun encode(fields: Map<String, Any?>): ByteArray {
        val builder = StringBuilder()
        builder.append('{')
        fields.entries.sortedBy { it.key }.forEachIndexed { index, entry ->
            if (index > 0) builder.append(',')
            appendText(builder, entry.key)
            builder.append(':')
            when (val value = entry.value) {
                null -> builder.append("null")
                is String -> appendText(builder, value)
                is Long -> builder.append(value.toString())
                is Boolean -> builder.append(if (value) "true" else "false")
                else -> throw IllegalArgumentException("Unsupported canonical JSON value")
            }
        }
        builder.append('}')
        return builder.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): Map<String, Any?> {
        require(bytes.size <= REGISTRY_MAX_BYTES) { "Canonical JSON exceeds the registry bound" }
        val text = try {
            bytes.toString(Charsets.UTF_8)
        } catch (_: Throwable) {
            throw IllegalArgumentException("Canonical JSON is not valid UTF-8")
        }
        require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
            "Canonical JSON is not valid UTF-8"
        }
        return Reader(text).readObject()
    }

    private fun appendText(builder: StringBuilder, value: String) {
        builder.append('"')
        value.forEach { character ->
            when {
                character == '"' -> builder.append("\\\"")
                character == '\\' -> builder.append("\\\\")
                character.code < 0x20 -> {
                    builder.append("\\u00")
                    builder.append(HEX_DIGITS[character.code ushr 4])
                    builder.append(HEX_DIGITS[character.code and 0x0f])
                }
                else -> builder.append(character)
            }
        }
        builder.append('"')
    }

    private class Reader(private val text: String) {
        private var index = 0

        fun readObject(): Map<String, Any?> {
            expect('{')
            val fields = LinkedHashMap<String, Any?>()
            var previousKey: String? = null
            if (peek() != '}') {
                while (true) {
                    val key = readText()
                    require(previousKey == null || previousKey < key) {
                        "Canonical JSON keys must ascend without duplicates"
                    }
                    previousKey = key
                    expect(':')
                    fields[key] = readValue()
                    if (peek() != ',') break
                    expect(',')
                }
            }
            expect('}')
            require(index == text.length) { "Canonical JSON has trailing content" }
            return fields
        }

        private fun readValue(): Any? = when (peek()) {
            '"' -> readText()
            't' -> readLiteral("true", true)
            'f' -> readLiteral("false", false)
            'n' -> readLiteral("null", null)
            else -> readNumber()
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            require(text.startsWith(literal, index)) { "Canonical JSON literal is invalid" }
            index += literal.length
            return value
        }

        private fun readNumber(): Long {
            val start = index
            if (peek() == '-') index += 1
            val digitsStart = index
            while (index < text.length && text[index] in '0'..'9') index += 1
            val digits = text.substring(digitsStart, index)
            require(digits.isNotEmpty()) { "Canonical JSON number is invalid" }
            require(digits == "0" || digits[0] != '0') { "Canonical JSON number is not canonical" }
            val encoded = text.substring(start, index)
            require(encoded != "-0") { "Canonical JSON number is not canonical" }
            return encoded.toLongOrNull()
                ?: throw IllegalArgumentException("Canonical JSON number is out of range")
        }

        private fun readText(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                require(index < text.length) { "Canonical JSON text is unterminated" }
                when (val character = text[index]) {
                    '"' -> {
                        index += 1
                        return builder.toString()
                    }
                    '\\' -> {
                        index += 1
                        require(index < text.length) { "Canonical JSON escape is unterminated" }
                        when (text[index]) {
                            '"' -> builder.append('"').also { index += 1 }
                            '\\' -> builder.append('\\').also { index += 1 }
                            'u' -> builder.append(readEscapedControl())
                            else -> throw IllegalArgumentException(
                                "Canonical JSON escape is not canonical",
                            )
                        }
                    }
                    else -> {
                        require(character.code >= 0x20) {
                            "Canonical JSON text holds an unescaped control character"
                        }
                        builder.append(character)
                        index += 1
                    }
                }
            }
        }

        private fun readEscapedControl(): Char {
            require(index + 5 <= text.length) { "Canonical JSON escape is unterminated" }
            val digits = text.substring(index + 1, index + 5)
            require(digits.startsWith("00")) { "Canonical JSON escape is not canonical" }
            require(digits.drop(2).all { it in '0'..'9' || it in 'a'..'f' }) {
                "Canonical JSON escape is not canonical"
            }
            val code = digits.toInt(16)
            require(code < 0x20) { "Canonical JSON escape is not canonical" }
            index += 5
            return code.toChar()
        }

        private fun peek(): Char {
            require(index < text.length) { "Canonical JSON ended early" }
            return text[index]
        }

        private fun expect(character: Char) {
            require(index < text.length && text[index] == character) {
                "Canonical JSON is not canonical"
            }
            index += 1
        }
    }
}
