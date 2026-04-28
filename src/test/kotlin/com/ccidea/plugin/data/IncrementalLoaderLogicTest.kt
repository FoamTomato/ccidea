package com.ccidea.plugin.data

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.RandomAccessFile
import java.nio.file.Files

/**
 * Verifies the byte-offset advancement rule used by UsageDataLoader: only advance the
 * offset to one byte past the last confirmed '\n'. We exercise the logic directly instead
 * of relying on the IntelliJ-bound service.
 */
class IncrementalLoaderLogicTest {

    private fun readNewLines(file: java.io.File, startOffset: Long): Pair<List<String>, Long> {
        val out = mutableListOf<String>()
        var lineStart = startOffset
        var cursor = startOffset
        val buf = StringBuilder()
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(startOffset)
            val byteBuf = ByteArray(4096)
            while (true) {
                val read = raf.read(byteBuf)
                if (read <= 0) break
                for (i in 0 until read) {
                    val b = byteBuf[i].toInt() and 0xFF
                    cursor++
                    if (b == 0x0A) {
                        out += buf.toString().trimEnd('\r')
                        buf.setLength(0)
                        lineStart = cursor
                    } else buf.append(b.toChar())
                }
            }
        }
        return out to lineStart
    }

    @Test
    fun `only fully terminated lines are emitted`() {
        val tmp = Files.createTempFile("ccidea", ".jsonl").toFile()
        tmp.writeText("alpha\nbeta\npartial")
        val (lines, off) = readNewLines(tmp, 0)
        assertThat(lines).containsExactly("alpha", "beta")
        // After two newlines: 6 + 5 = 11 bytes consumed, so lineStart should be 11.
        assertThat(off).isEqualTo(11L)
        tmp.delete()
    }

    @Test
    fun `appended bytes are picked up on next read`() {
        val tmp = Files.createTempFile("ccidea", ".jsonl").toFile()
        tmp.writeText("alpha\n")
        val (first, off1) = readNewLines(tmp, 0)
        assertThat(first).containsExactly("alpha")
        tmp.appendText("beta\ngamma\n")
        val (second, off2) = readNewLines(tmp, off1)
        assertThat(second).containsExactly("beta", "gamma")
        assertThat(off2).isEqualTo(tmp.length())
        tmp.delete()
    }
}
