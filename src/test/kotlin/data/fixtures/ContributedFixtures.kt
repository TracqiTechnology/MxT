package data.fixtures

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlin.test.assertEquals

internal object ContributedFixtures {
    private val manifest = Json.parseToJsonElement(File("tools/fixtures/contributed.json").readText()).jsonArray
    val paths = manifest.map { it.jsonObject.getValue("path").jsonPrimitive.content }
    fun file(path: String): File {
        val item = manifest.single { it.jsonObject.getValue("path").jsonPrimitive.content == path }.jsonObject
        val file = File(System.getenv("MXT_CONTRIBUTED_FIXTURES") ?: "build/contributed-fixtures", path)
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(item.getValue("sha256").jsonPrimitive.content, hash, "Fixture checksum: $path")
        return file
    }
}
