package data.sniffer

import data.model.EcuPlatform

/**
 * Builds byte-pattern signatures from a reference BIN with known RAM addresses.
 *
 * For each known variable, finds all code references to its address in the binary,
 * extracts a context window around each reference, and replaces the address bytes
 * with wildcards. The resulting patterns can be matched against other BINs of
 * the same ECU family to discover relocated addresses.
 */
object SignatureBuilder {

    /** Bytes of context on each side of the address reference. */
    private const val CONTEXT_WINDOW = 16

    /** Maximum signatures to keep per variable. */
    private const val MAX_SIGNATURES_PER_VAR = 5

    /** Minimum quality score to keep a signature. */
    private const val MIN_QUALITY = 0.3

    /**
     * Build a signature database from a reference binary and known RAM variables.
     *
     * @param referenceBin The raw binary content (code segments)
     * @param baseAddress The base address of the binary (e.g., 0x800000 for ME7 flash)
     * @param variables Known RAM variables with their addresses
     * @param platform ECU platform (determines address encoding)
     * @param referenceId Identifier for this reference (e.g., software version)
     */
    fun build(
        referenceBin: ByteArray,
        baseAddress: Long,
        variables: List<RamVariable>,
        platform: EcuPlatform,
        referenceId: String = ""
    ): SignatureDatabase {
        val addressWidth = platform.addressWidth
        val allSignatures = mutableMapOf<String, List<SignaturePattern>>()
        val varMap = mutableMapOf<String, RamVariable>()

        // Collect all address encodings to detect collisions
        val addressEncodings = mutableMapOf<String, MutableList<String>>()
        for (v in variables) {
            val encoded = encodeAddress(v.address, platform)
            val key = encoded.joinToString(",") { it.toString() }
            addressEncodings.getOrPut(key) { mutableListOf() }.add(v.name)
        }

        for (variable in variables) {
            varMap[variable.name] = variable
            val encoded = encodeAddress(variable.address, platform)

            // Skip degenerate addresses (all zeros or all 0xFF)
            if (encoded.all { it == 0.toByte() } || encoded.all { it == 0xFF.toByte() }) continue

            val occurrences = findAllOccurrences(referenceBin, encoded)

            // Filter to code ranges only
            val codeOccurrences = occurrences.filter { offset ->
                val absoluteAddr = baseAddress + offset
                platform.codeRanges.any { absoluteAddr in it }
            }

            val patterns = codeOccurrences.mapNotNull { offset ->
                buildPattern(referenceBin, offset, addressWidth)
            }

            // Score by uniqueness
            val collisionCount = addressEncodings[encoded.joinToString(",") { it.toString() }]?.size ?: 1
            val scored = patterns.map { pattern ->
                val uniquenessScore = 1.0 - (countPatternMatches(referenceBin, pattern) - 1).coerceAtLeast(0) / variables.size.toDouble()
                pattern.copy(quality = uniquenessScore * (1.0 / collisionCount))
            }

            val filtered = scored
                .filter { it.quality >= MIN_QUALITY }
                .sortedByDescending { it.quality }
                .take(MAX_SIGNATURES_PER_VAR)

            if (filtered.isNotEmpty()) {
                allSignatures[variable.name] = filtered
            }
        }

        return SignatureDatabase(
            platform = platform,
            referenceId = referenceId,
            variables = varMap,
            signatures = allSignatures
        )
    }

    /**
     * Encode a RAM address as the bytes that would appear in code.
     *
     * C167 (ME7/Motronic): 16-bit DPP offset, little-endian (strip $38xxxx → $xxxx)
     * TriCore (MED9/MED17): 32-bit absolute address, little-endian
     */
    internal fun encodeAddress(address: Long, platform: EcuPlatform): ByteArray {
        return when (platform.addressWidth) {
            2 -> {
                // C167: RAM $38xxxx → DPP3 offset $xxxx (lower 16 bits)
                val offset = (address and 0xFFFF).toInt()
                byteArrayOf(
                    (offset and 0xFF).toByte(),
                    ((offset shr 8) and 0xFF).toByte()
                )
            }
            4 -> {
                // TriCore: full 32-bit address, little-endian
                byteArrayOf(
                    (address and 0xFF).toByte(),
                    ((address shr 8) and 0xFF).toByte(),
                    ((address shr 16) and 0xFF).toByte(),
                    ((address shr 24) and 0xFF).toByte()
                )
            }
            else -> throw IllegalArgumentException("Unsupported address width: ${platform.addressWidth}")
        }
    }

    /**
     * Decode address bytes from a pattern match back to a full RAM address.
     */
    internal fun decodeAddress(bytes: ByteArray, platform: EcuPlatform): Long {
        return when (platform.addressWidth) {
            2 -> {
                val offset = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
                platform.ramRangeStart + offset.toLong()
            }
            4 -> {
                (bytes[0].toLong() and 0xFF) or
                    ((bytes[1].toLong() and 0xFF) shl 8) or
                    ((bytes[2].toLong() and 0xFF) shl 16) or
                    ((bytes[3].toLong() and 0xFF) shl 24)
            }
            else -> throw IllegalArgumentException("Unsupported address width: ${platform.addressWidth}")
        }
    }

    /** Find all byte-offset positions where [needle] appears in [haystack]. */
    private fun findAllOccurrences(haystack: ByteArray, needle: ByteArray): List<Int> {
        val results = mutableListOf<Int>()
        val limit = haystack.size - needle.size
        outer@ for (i in 0..limit) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            results.add(i)
        }
        return results
    }

    /** Build a signature pattern from a binary at a given address-reference offset. */
    private fun buildPattern(bin: ByteArray, addressOffset: Int, addressWidth: Int): SignaturePattern? {
        val start = addressOffset - CONTEXT_WINDOW
        val end = addressOffset + addressWidth + CONTEXT_WINDOW

        if (start < 0 || end > bin.size) return null

        val totalSize = end - start
        val bytes = ByteArray(totalSize)
        val mask = ByteArray(totalSize) { 0xFF.toByte() }
        val addrIndices = IntArray(addressWidth)

        System.arraycopy(bin, start, bytes, 0, totalSize)

        // Set address byte positions as wildcards
        val addrStart = CONTEXT_WINDOW
        for (i in 0 until addressWidth) {
            mask[addrStart + i] = 0x00
            addrIndices[i] = addrStart + i
        }

        return SignaturePattern(
            bytes = bytes,
            mask = mask,
            addressByteIndices = addrIndices,
            quality = 0.0  // scored later
        )
    }

    /** Count how many times a pattern matches in the binary (mask-aware). */
    private fun countPatternMatches(bin: ByteArray, pattern: SignaturePattern): Int {
        var count = 0
        val patLen = pattern.bytes.size
        val limit = bin.size - patLen
        outer@ for (i in 0..limit) {
            for (j in 0 until patLen) {
                if (pattern.mask[j] != 0.toByte() && bin[i + j] != pattern.bytes[j]) {
                    continue@outer
                }
            }
            count++
        }
        return count
    }
}
