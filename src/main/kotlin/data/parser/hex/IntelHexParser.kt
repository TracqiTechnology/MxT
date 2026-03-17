package data.parser.hex

import java.io.File

/**
 * Parses Intel HEX format files into a flat byte array.
 *
 * Supports record types:
 * - 00: Data
 * - 01: End of File
 * - 02: Extended Segment Address
 * - 04: Extended Linear Address
 *
 * The output byte array is zero-indexed from the lowest data address found.
 */
object IntelHexParser {

    fun parse(file: File): ByteArray = parse(file.readText())

    fun parse(text: String): ByteArray {
        var extendedAddress: Long = 0
        var minAddress = Long.MAX_VALUE
        var maxAddress = Long.MIN_VALUE

        // First pass: determine address range
        val dataRecords = mutableListOf<Triple<Long, Int, String>>() // absoluteAddr, byteCount, hexData

        for (line in text.lines()) {
            val trimmed = line.trim().trimEnd('\r')
            if (!trimmed.startsWith(":")) continue

            val hex = trimmed.substring(1)
            if (hex.length < 10) continue

            val byteCount = hex.substring(0, 2).toInt(16)
            val address = hex.substring(2, 6).toInt(16)
            val recordType = hex.substring(6, 8).toInt(16)
            val dataEnd = 8 + byteCount * 2
            if (hex.length < dataEnd) continue
            val dataHex = hex.substring(8, dataEnd)

            when (recordType) {
                0x00 -> { // Data
                    val absoluteAddr = extendedAddress + address
                    dataRecords.add(Triple(absoluteAddr, byteCount, dataHex))
                    if (absoluteAddr < minAddress) minAddress = absoluteAddr
                    val end = absoluteAddr + byteCount
                    if (end > maxAddress) maxAddress = end
                }
                0x01 -> break // EOF
                0x02 -> { // Extended Segment Address
                    extendedAddress = dataHex.substring(0, 4).toLong(16) shl 4
                }
                0x04 -> { // Extended Linear Address
                    extendedAddress = dataHex.substring(0, 4).toLong(16) shl 16
                }
            }
        }

        if (dataRecords.isEmpty()) return ByteArray(0)

        val size = (maxAddress - minAddress).toInt()
        val result = ByteArray(size)

        for ((absoluteAddr, byteCount, dataHex) in dataRecords) {
            val offset = (absoluteAddr - minAddress).toInt()
            for (i in 0 until byteCount) {
                result[offset + i] = dataHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        return result
    }

    /** Returns the base (minimum) address found in the HEX file. */
    fun parseWithBaseAddress(file: File): Pair<Long, ByteArray> = parseWithBaseAddress(file.readText())

    fun parseWithBaseAddress(text: String): Pair<Long, ByteArray> {
        var extendedAddress: Long = 0
        var minAddress = Long.MAX_VALUE
        var maxAddress = Long.MIN_VALUE
        val dataRecords = mutableListOf<Triple<Long, Int, String>>()

        for (line in text.lines()) {
            val trimmed = line.trim().trimEnd('\r')
            if (!trimmed.startsWith(":")) continue

            val hex = trimmed.substring(1)
            if (hex.length < 10) continue

            val byteCount = hex.substring(0, 2).toInt(16)
            val address = hex.substring(2, 6).toInt(16)
            val recordType = hex.substring(6, 8).toInt(16)
            val dataHex = hex.substring(8, 8 + byteCount * 2)

            when (recordType) {
                0x00 -> {
                    val absoluteAddr = extendedAddress + address
                    dataRecords.add(Triple(absoluteAddr, byteCount, dataHex))
                    if (absoluteAddr < minAddress) minAddress = absoluteAddr
                    val end = absoluteAddr + byteCount
                    if (end > maxAddress) maxAddress = end
                }
                0x01 -> break
                0x02 -> { extendedAddress = dataHex.substring(0, 4).toLong(16) shl 4 }
                0x04 -> { extendedAddress = dataHex.substring(0, 4).toLong(16) shl 16 }
            }
        }

        if (dataRecords.isEmpty()) return Pair(0L, ByteArray(0))

        val size = (maxAddress - minAddress).toInt()
        val result = ByteArray(size)

        for ((absoluteAddr, byteCount, dataHex) in dataRecords) {
            val offset = (absoluteAddr - minAddress).toInt()
            for (i in 0 until byteCount) {
                result[offset + i] = dataHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        return Pair(minAddress, result)
    }
}
