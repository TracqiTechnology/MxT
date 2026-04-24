package data.sniffer

import data.model.EcuPlatform

/**
 * A single byte-pattern signature for locating a RAM variable reference in code.
 *
 * The [bytes] array contains the full context window around a code reference
 * to a RAM address. The [mask] array indicates which bytes must match exactly
 * (0xFF) and which are wildcards holding address bytes (0x00).
 */
data class SignaturePattern(
    val bytes: ByteArray,
    val mask: ByteArray,
    val addressByteIndices: IntArray,
    val quality: Double
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignaturePattern) return false
        return bytes.contentEquals(other.bytes) && mask.contentEquals(other.mask) &&
            addressByteIndices.contentEquals(other.addressByteIndices) && quality == other.quality
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mask.contentHashCode()
        result = 31 * result + addressByteIndices.contentHashCode()
        result = 31 * result + quality.hashCode()
        return result
    }
}

/**
 * Database of signatures built from a reference BIN + known addresses.
 */
data class SignatureDatabase(
    val platform: EcuPlatform,
    val referenceId: String,
    val variables: Map<String, RamVariable>,
    val signatures: Map<String, List<SignaturePattern>>
)

/**
 * A known RAM variable from a reference source (DAMOS, A2L, or .ecu file).
 */
data class RamVariable(
    val name: String,
    val alias: String = "",
    val address: Long,
    val size: Int,
    val unit: String = "",
    val factor: Double = 1.0,
    val offset: Double = 0.0,
    val signed: Boolean = false,
    val inverse: Boolean = false,
    val description: String = ""
)

/**
 * Result of scanning a target BIN for a single variable.
 */
data class SniffResult(
    val variableName: String,
    val discoveredAddress: Long,
    val confidence: Double,
    val matchCount: Int,
    val agreementCount: Int,
    val candidateAddresses: Map<Long, Int>
)
