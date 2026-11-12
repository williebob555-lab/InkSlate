package com.inkslate

import com.inkslate.data.SystemFileImport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SystemFileImportTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun zipImportKeepsNestedFiles() {
        val destination = temporary.newFolder("library")
        val bytes = zipOf(
            "score.pdf" to byteArrayOf(1, 2, 3),
            "parts/trombone.pdf" to byteArrayOf(4, 5)
        )

        val imported = SystemFileImport.extractZip(
            ByteArrayInputStream(bytes), destination, "Concert"
        ).single()

        assertEquals("Concert", imported.name)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(imported, "score.pdf").readBytes())
        assertArrayEquals(byteArrayOf(4, 5), File(imported, "parts/trombone.pdf").readBytes())
    }

    @Test
    fun zipImportDoesNotOverwriteAnExistingArchiveFolder() {
        val destination = temporary.newFolder("library")
        File(destination, "Concert").mkdirs()

        val imported = SystemFileImport.extractZip(
            ByteArrayInputStream(zipOf("score.pdf" to byteArrayOf(9))),
            destination,
            "Concert"
        ).single()

        assertEquals("Concert (2)", imported.name)
        assertTrue(File(imported, "score.pdf").isFile)
    }

    @Test
    fun unsafeZipPathIsRejectedAndPartialImportIsRemoved() {
        val destination = temporary.newFolder("library")
        val bytes = zipOf(
            "safe.pdf" to byteArrayOf(1),
            "../outside.pdf" to byteArrayOf(2)
        )

        val failure = runCatching {
            SystemFileImport.extractZip(ByteArrayInputStream(bytes), destination, "Unsafe")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertFalse(File(destination, "Unsafe").exists())
        assertFalse(File(destination.parentFile, "outside.pdf").exists())
    }

    private fun zipOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, contents) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
