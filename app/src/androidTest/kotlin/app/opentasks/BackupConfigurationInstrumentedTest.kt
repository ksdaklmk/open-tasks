package app.opentasks

import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import app.opentasks.backup.AndroidAtomicPackageFile
import app.opentasks.backup.AndroidBackupFiles
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser

class BackupConfigurationInstrumentedTest {
    @Test
    fun packagedManifestAndBackupResourcesExposeOnlyPortablePackage() {
        val context = ApplicationProvider.getApplicationContext<OpenTasksApplication>()
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertTrue(info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)

        val extraction = parseRules(context.resources.getXml(R.xml.data_extraction_rules))
        assertEquals(
            listOf(
                Rule("cloud-backup", "include", "file", PORTABLE_PATH),
                Rule("device-transfer", "include", "file", PORTABLE_PATH),
            ),
            extraction.rules,
        )
        assertEquals("true", extraction.cloudEncryptionRequired)

        val legacy = parseRules(context.resources.getXml(R.xml.backup_rules))
        assertEquals(
            setOf(
                "root",
                "file",
                "database",
                "sharedpref",
                "external",
                "device_root",
                "device_file",
                "device_database",
                "device_sharedpref",
            ),
            legacy.rules.filter { it.element == "exclude" }.map(Rule::domain).toSet(),
        )
        assertTrue(legacy.rules.none { it.element == "include" })
        assertTrue(legacy.rules.all { it.path == "." })
    }

    @Test
    fun remoteTransferAndRecoveryStagingRootsStayUnderTheNoBackupDirectoryAndOutsideEveryIncludeRule() {
        val context = ApplicationProvider.getApplicationContext<OpenTasksApplication>()
        val files = AndroidBackupFiles(context)
        val noBackupPath = context.noBackupFilesDir.path

        assertTrue(files.remoteTransferRoot.path.startsWith("$noBackupPath${File.separator}"))
        assertTrue(files.recoveryRoot.path.startsWith("$noBackupPath${File.separator}"))

        // Every include rule names only the portable package; since Android backup
        // (classic and Auto Backup) never inspects noBackupFilesDir, the new roots
        // above are excluded both structurally and by this exhaustive allow-list.
        val extraction = parseRules(context.resources.getXml(R.xml.data_extraction_rules))
        val includedPaths = extraction.rules.filter { it.element == "include" }.map(Rule::path)
        assertEquals(listOf(PORTABLE_PATH, PORTABLE_PATH), includedPaths)
    }

    @Test
    fun publisherAtomicSuccessAndFailureLeaveNoEligibleSidecars() {
        val context = ApplicationProvider.getApplicationContext<OpenTasksApplication>()
        val directory = File(context.filesDir, "android_backup")
        directory.mkdirs()
        val packageFile = File(directory, "open_tasks_portable_v1.otb")
        val atomic = AndroidAtomicPackageFile(packageFile)

        atomic.startWrite().also { stream ->
            stream.write("verified-package".toByteArray())
            atomic.finishWrite(stream)
        }
        assertNoSidecars(directory)

        atomic.startWrite().also { stream ->
            stream.write("failed-candidate".toByteArray())
            atomic.failWrite(stream)
        }
        assertNoSidecars(directory)
        assertTrue(packageFile.delete())
    }

    private fun parseRules(parser: XmlPullParser): ParsedRules {
        val rules = mutableListOf<Rule>()
        var section = ""
        var encryptionRequired: String? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "cloud-backup", "device-transfer", "full-backup-content" -> {
                        section = parser.name
                        if (section == "cloud-backup") {
                            encryptionRequired = parser.getAttributeValue(
                                null,
                                "disableIfNoEncryptionCapabilities",
                            )
                        }
                    }
                    "include", "exclude" -> rules += Rule(
                        section = section,
                        element = parser.name,
                        domain = checkNotNull(parser.getAttributeValue(null, "domain")),
                        path = checkNotNull(parser.getAttributeValue(null, "path")),
                    )
                }
            }
            parser.next()
        }
        return ParsedRules(rules, encryptionRequired)
    }

    private fun assertNoSidecars(directory: File) {
        val names = directory.list()?.toList().orEmpty()
        assertFalse(names.any { it.endsWith(".new") })
        assertFalse(names.any { it.endsWith(".tmp") })
        assertFalse(names.any { it.endsWith(".bak") })
    }

    private data class ParsedRules(
        val rules: List<Rule>,
        val cloudEncryptionRequired: String?,
    )

    private data class Rule(
        val section: String,
        val element: String,
        val domain: String,
        val path: String,
    )

    private companion object {
        const val PORTABLE_PATH = "android_backup/open_tasks_portable_v1.otb"
    }
}
