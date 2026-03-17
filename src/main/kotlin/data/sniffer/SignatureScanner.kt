package data.sniffer

/**
 * Scans an unknown BIN for signature matches to discover RAM addresses.
 *
 * For each variable in the [SignatureDatabase], matches its signatures against
 * the target binary. Multiple signatures voting for the same address increase
 * confidence. Results are validated against the platform's RAM address range.
 */
object SignatureScanner {

    /**
     * Scan a target BIN for all variables in the signature database.
     *
     * @param targetBin Raw binary content of the target ECU flash
     * @param database Signature database built from a reference BIN
     * @return List of discovered variables with addresses and confidence scores
     */
    fun scan(targetBin: ByteArray, database: SignatureDatabase): List<SniffResult> {
        val platform = database.platform
        val results = mutableListOf<SniffResult>()

        for ((varName, signatures) in database.signatures) {
            val candidateVotes = mutableMapOf<Long, Int>()
            var totalMatches = 0

            for (signature in signatures) {
                val matches = findPatternMatches(targetBin, signature)
                for (matchOffset in matches) {
                    val addressBytes = extractAddressBytes(targetBin, matchOffset, signature)
                    val address = SignatureBuilder.decodeAddress(addressBytes, platform)

                    // Validate: address must be in platform's RAM range
                    if (address in platform.ramRangeStart..platform.ramRangeEnd) {
                        candidateVotes[address] = (candidateVotes[address] ?: 0) + 1
                        totalMatches++
                    }
                }
            }

            if (candidateVotes.isEmpty()) continue

            val bestAddress = candidateVotes.maxByOrNull { it.value }!!
            val agreementCount = bestAddress.value
            val confidence = if (totalMatches > 0) agreementCount.toDouble() / totalMatches else 0.0

            results.add(
                SniffResult(
                    variableName = varName,
                    discoveredAddress = bestAddress.key,
                    confidence = confidence,
                    matchCount = totalMatches,
                    agreementCount = agreementCount,
                    candidateAddresses = candidateVotes
                )
            )
        }

        return results.sortedByDescending { it.confidence }
    }

    /**
     * Find all positions where the signature pattern matches in the target binary.
     * Returns the offset of the start of the pattern (not the address bytes).
     */
    private fun findPatternMatches(bin: ByteArray, pattern: SignaturePattern): List<Int> {
        val results = mutableListOf<Int>()
        val patLen = pattern.bytes.size
        val limit = bin.size - patLen
        if (limit < 0) return results

        outer@ for (i in 0..limit) {
            for (j in 0 until patLen) {
                if (pattern.mask[j] != 0.toByte() && bin[i + j] != pattern.bytes[j]) {
                    continue@outer
                }
            }
            results.add(i)
        }
        return results
    }

    /**
     * Extract address bytes from a match position using the signature's wildcard indices.
     */
    private fun extractAddressBytes(bin: ByteArray, matchOffset: Int, pattern: SignaturePattern): ByteArray {
        val addressWidth = pattern.addressByteIndices.size
        val result = ByteArray(addressWidth)
        for (i in 0 until addressWidth) {
            result[i] = bin[matchOffset + pattern.addressByteIndices[i]]
        }
        return result
    }
}
