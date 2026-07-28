package data.writer

import data.parser.bin.BinParser
import data.parser.xdf.AxisDefinition
import data.parser.xdf.TableDefinition
import data.parser.xdf.XdfParser
import domain.math.map.Map3d
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Trusted-CI validation for the private MED17 OTS corpus.
 *
 * The normal `test` task excludes this tag. `internalFixtureTest` requires the
 * private fixture root explicitly and fails before test execution if it is
 * unavailable.
 */
@Tag("internal-fixture")
class Med17PrivateFixtureCorpusTest {

    private data class ManifestFile(
        val variant: String,
        val xdfVariant: String,
        val path: String,
        val sha256: String
    )

    private data class Corpus(
        val xdfs: List<ManifestFile>,
        val binaries: List<ManifestFile>
    )

    private val fixtureRoot: File =
        File(System.getProperty("mxt.internal.fixtures"))

    private val corpus: Corpus by lazy {
        val manifest = File(fixtureRoot, "mxt-fixtures/med17-corpus-v1.json")
        assertTrue(manifest.isFile, "Private fixture manifest is missing: ${manifest.path}")
        val root = Json.parseToJsonElement(manifest.readText()).jsonObject
        assertEquals(1, root.getValue("schemaVersion").jsonPrimitive.content.toInt())

        fun entries(name: String): List<ManifestFile> =
            root.getValue(name).jsonArray.map { element ->
                val item = element.jsonObject
                ManifestFile(
                    variant = item.getValue("variant").jsonPrimitive.content,
                    xdfVariant = item["xdfVariant"]?.jsonPrimitive?.content
                        ?: item.getValue("variant").jsonPrimitive.content,
                    path = item.getValue("path").jsonPrimitive.content,
                    sha256 = item.getValue("sha256").jsonPrimitive.content
                )
            }

        Corpus(
            xdfs = entries("normalXdfs"),
            binaries = entries("tunedBinaries")
        )
    }

    @Test
    fun `private corpus contains every expected XDF and tuned BIN with reviewed hashes`() {
        assertEquals(
            setOf("404A", "404E", "404G", "404H", "404J", "404K", "404L"),
            corpus.xdfs.map { it.variant }.toSet()
        )
        assertEquals(17, corpus.binaries.size, "Every tuned MED17 OTS BIN must be covered")
        assertTrue(corpus.binaries.none { "STOCK" in it.path.uppercase() })

        (corpus.xdfs + corpus.binaries).forEach { entry ->
            val file = File(fixtureRoot, entry.path)
            assertTrue(file.isFile, "${entry.variant}: private fixture is missing: ${entry.path}")
            assertEquals(
                entry.sha256,
                sha256(file),
                "${entry.variant}: private fixture changed without a manifest review: ${entry.path}"
            )
        }
    }

    @Test
    fun `every tuned BIN has a compatible normal XDF`() {
        val definitionsByVariant = loadDefinitionsByVariant()
        corpus.binaries.forEach { entry ->
            val source = File(fixtureRoot, entry.path)
            val selected = definitionsByVariant.getValue(entry.xdfVariant).let { definitions ->
                REQUIRED_TABLES.map { table -> definitions.first { it.tableName == table } }
            }
            val parsed = BinParser.parseToList(FileInputStream(source), selected)
            assertEquals(REQUIRED_TABLES.size, parsed.size, "${entry.path}: required map count")
            parsed.forEach { (definition, map) ->
                assertTrue(
                    isWellFormed(definition, map),
                    "${entry.variant} using ${entry.xdfVariant}: malformed " +
                        "'${definition.tableName}' in ${entry.path}"
                )
            }
        }
    }

    @Test
    fun `all private normal XDFs expose writable MED17 calibration tables`() {
        corpus.xdfs.forEach { entry ->
            val xdf = File(fixtureRoot, entry.path)
            val (_, definitions) = XdfParser.parseToList(FileInputStream(xdf))
            assertTrue(definitions.isNotEmpty(), "${entry.variant}: XDF contains no definitions")
            REQUIRED_TABLES.forEach { table ->
                val definition = definitions.firstOrNull { it.tableName == table }
                assertNotNull(
                    definition,
                    "${entry.variant}: required calibration '$table' is missing"
                )
                assertTrue(
                    definition.zAxis.rowCount > 0 && definition.zAxis.columnCount > 0,
                    "${entry.variant}: '$table' has invalid dimensions"
                )
            }
        }
    }

    @Test
    fun `all tuned BINs parse and identity-write required maps without changing a byte`() {
        val definitionsByVariant = loadDefinitionsByVariant()
        corpus.binaries.forEach { entry ->
            val source = File(fixtureRoot, entry.path)
            val definitions = definitionsByVariant.getValue(entry.xdfVariant)
            val selectedDefinitions = REQUIRED_TABLES.map { table ->
                definitions.first { it.tableName == table }
            }
            val maps = BinParser.parseToList(
                FileInputStream(source),
                selectedDefinitions
            )
            assertEquals(
                selectedDefinitions.size,
                maps.size,
                "${entry.variant}: every required map must parse from ${entry.path}"
            )
            maps.forEach { (definition, map) ->
                assertMapIsFiniteAndWellFormed(entry, definition, map)
            }

            val output = File.createTempFile("mxt-private-identity-", ".bin")
            try {
                source.copyTo(output, overwrite = true)
                BinWriter.writeBatch(output, maps)
                assertTrue(
                    source.readBytes().contentEquals(output.readBytes()),
                    "${entry.variant}: identity write changed bytes in ${entry.path}"
                )
            } finally {
                output.delete()
            }
        }
    }

    @Test
    fun `all tuned BINs accept a surgical KFMIOP edit with exact readback and no spill`() {
        val definitionsByVariant = loadDefinitionsByVariant()
        corpus.binaries.forEach { entry ->
            val source = File(fixtureRoot, entry.path)
            val definition = definitionsByVariant.getValue(entry.xdfVariant)
                .first { it.tableName == KFMIOP }
            val original = BinParser.parseToList(
                FileInputStream(source),
                listOf(definition)
            ).single().second
            assertMapIsFiniteAndWellFormed(entry, definition, original)

            val edited = Map3d(original)
            val row = edited.zAxis.size / 2
            val column = edited.zAxis[row].size / 2
            edited.zAxis[row][column] += 1.0

            val output = File.createTempFile("mxt-private-edit-", ".bin")
            try {
                source.copyTo(output, overwrite = true)
                BinWriter.write(output, definition, edited)
                assertOnlyDefinitionBytesChanged(source, output, definition, entry)

                val readBack = BinParser.parseToList(
                    FileInputStream(output),
                    listOf(definition)
                ).single().second
                assertTrue(
                    abs(readBack.zAxis[row][column] - edited.zAxis[row][column]) <= 0.05,
                    "${entry.variant}: KFMIOP edited cell did not round-trip in ${entry.path}"
                )
                assertTrue(
                    original.xAxis.contentEquals(readBack.xAxis),
                    "${entry.variant}: KFMIOP edit changed the decoded X axis"
                )
                assertTrue(
                    original.yAxis.contentEquals(readBack.yAxis),
                    "${entry.variant}: KFMIOP edit changed the decoded Y axis"
                )
            } finally {
                output.delete()
            }
        }
    }

    private fun loadDefinitionsByVariant(): Map<String, List<TableDefinition>> =
        corpus.xdfs.associate { entry ->
            entry.variant to XdfParser.parseToList(
                FileInputStream(File(fixtureRoot, entry.path))
            ).second
        }

    private fun assertMapIsFiniteAndWellFormed(
        entry: ManifestFile,
        definition: TableDefinition,
        map: Map3d
    ) {
        val context =
            "${entry.variant} using ${entry.xdfVariant}: ${definition.tableName} in ${entry.path}"
        assertTrue(map.xAxis.all { it.isFinite() }, "$context has a non-finite X axis")
        assertTrue(map.yAxis.all { it.isFinite() }, "$context has a non-finite Y axis")
        assertTrue(map.zAxis.all { row -> row.all { it.isFinite() } }, "$context has non-finite Z data")
        assertEquals(maxOf(definition.zAxis.rowCount, 1), map.zAxis.size, "$context row count")
        assertTrue(
            map.zAxis.all { it.size == maxOf(definition.zAxis.columnCount, 1) },
            "$context column count"
        )
        assertNativeAxisIsUsable(map.xAxis, "$context X axis")
        assertNativeAxisIsUsable(map.yAxis, "$context Y axis")
    }

    private fun isWellFormed(definition: TableDefinition, map: Map3d): Boolean =
        map.xAxis.all { it.isFinite() } &&
            map.yAxis.all { it.isFinite() } &&
            map.zAxis.all { row -> row.all { it.isFinite() } } &&
            map.zAxis.size == maxOf(definition.zAxis.rowCount, 1) &&
            map.zAxis.all { it.size == maxOf(definition.zAxis.columnCount, 1) } &&
            isNativeAxisUsable(map.xAxis) &&
            isNativeAxisUsable(map.yAxis)

    private fun isNativeAxisUsable(axis: Array<Double>): Boolean =
        axis.size <= 1 || (
            axis.indices.drop(1).all { index -> axis[index] >= axis[index - 1] } &&
                axis.distinct().size >= 2
            )

    private fun assertNativeAxisIsUsable(axis: Array<Double>, context: String) {
        axis.indices.drop(1).forEach { index ->
            assertTrue(
                axis[index] >= axis[index - 1],
                "$context decreases at $index: ${axis.contentToString()}"
            )
        }
        assertTrue(
            axis.size <= 1 || axis.distinct().size >= 2,
            "$context has no usable range: ${axis.contentToString()}"
        )
    }

    private fun assertOnlyDefinitionBytesChanged(
        source: File,
        output: File,
        definition: TableDefinition,
        entry: ManifestFile
    ) {
        val before = source.readBytes()
        val after = output.readBytes()
        assertEquals(before.size, after.size, "${entry.variant}: BIN size changed")
        val allowed = listOfNotNull(
            byteRange(definition.xAxis, false),
            byteRange(definition.yAxis, false),
            byteRange(definition.zAxis, true)
        )
        var changed = 0
        before.indices.forEach { address ->
            if (before[address] != after[address]) {
                changed++
                assertTrue(
                    allowed.any { address in it },
                    "${entry.variant}: '${definition.tableName}' spilled to " +
                        "0x${address.toString(16)} in ${entry.path}"
                )
            }
        }
        assertTrue(changed > 0, "${entry.variant}: KFMIOP edit did not change encoded bytes")
    }

    private fun byteRange(axis: AxisDefinition?, zAxis: Boolean): IntRange? {
        axis ?: return null
        if (axis.address == 0) return null
        val count = if (zAxis) {
            maxOf(axis.rowCount, 1) * maxOf(axis.columnCount, 1)
        } else {
            maxOf(axis.indexCount, 1)
        }
        val bytes = count * (axis.sizeBits / 8)
        return axis.address until (axis.address + bytes)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val KFMIOP = "Opt eng tq"
        val REQUIRED_TABLES = listOf(
            KFMIOP,
            "Tgt filling",
            "Opt model ref ignition",
            "KF to linearize boost pressure = fTV",
            "LDR I controller limitation map"
        )
    }
}
