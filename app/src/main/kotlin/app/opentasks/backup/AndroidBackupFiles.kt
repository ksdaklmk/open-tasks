package app.opentasks.backup

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream

class AndroidBackupFiles(context: Context) {
    val eligiblePackage =
        File(context.filesDir, "android_backup/open_tasks_portable_v1.otb")
    val localBackupRoot =
        File(context.noBackupFilesDir, "backup/v1")
    val recoveryInbox =
        File(context.noBackupFilesDir, "recovery/incoming_android_v1.otb")
}

interface AtomicPackageFile {
    fun startWrite(): OutputStream
    fun finishWrite(stream: OutputStream)
    fun failWrite(stream: OutputStream)
    fun openRead(): InputStream
    fun length(): Long
    fun delete(): Boolean
}

class AndroidAtomicPackageFile(
    private val file: File,
    private val atomicFile: AtomicFile = AtomicFile(file),
) : AtomicPackageFile {
    private val writeCandidate = AndroidAtomicWriteCandidate(file)
    private var activeStream: OutputStream? = null

    override fun startWrite(): OutputStream {
        check(file.parentFile?.mkdirs() != false || file.parentFile?.isDirectory == true) {
            "Portable package directory is unavailable"
        }
        return atomicFile.startWrite().also { activeStream = it }
    }

    override fun finishWrite(stream: OutputStream) {
        check(stream === activeStream) { "Portable package stream is not active" }
        val fileStream = stream as java.io.FileOutputStream
        val candidateLength = writeCandidate.length()
        fileStream.fd.sync()
        atomicFile.finishWrite(fileStream)
        check(writeCandidate.wasCommitted(candidateLength)) {
            "Portable package candidate was not committed"
        }
        activeStream = null
    }

    override fun failWrite(stream: OutputStream) {
        if (stream === activeStream) {
            atomicFile.failWrite(stream as java.io.FileOutputStream)
            activeStream = null
        }
    }

    override fun openRead(): InputStream =
        if (activeStream != null) writeCandidate.openRead() else atomicFile.openRead()

    override fun length(): Long =
        if (activeStream != null) writeCandidate.length() else file.length()

    override fun delete(): Boolean {
        val backup = File("${file.path}.bak")
        val candidate = File("${file.path}.new")
        val existed = file.exists() || backup.exists() || candidate.exists()
        atomicFile.delete()
        candidate.delete()
        activeStream = null
        return !existed || (!file.exists() && !backup.exists() && !candidate.exists())
    }
}

internal class AndroidAtomicWriteCandidate(private val baseFile: File) {
    private val candidateFile = File("${baseFile.path}.new")

    fun openRead(): InputStream = FileInputStream(candidateFile)

    fun length(): Long = candidateFile.length()

    fun wasCommitted(expectedLength: Long): Boolean =
        !candidateFile.exists() &&
            baseFile.isFile &&
            baseFile.length() == expectedLength
}
