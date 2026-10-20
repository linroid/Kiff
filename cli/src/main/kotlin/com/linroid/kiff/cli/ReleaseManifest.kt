package com.linroid.kiff.cli

import com.linroid.kiff.io.KiffFiles
import java.io.IOException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * One file a release ships, and the patches that rebuild it from earlier releases.
 *
 * The manifest is read by every updater that looks for a patch, so it is a contract: a field may be
 * added, never renamed or given a new meaning. Anything else is a new [ReleaseManifest.SCHEMA].
 * Properties are declared in the order they are written.
 */
@Serializable
internal class ReleaseFile(
  val name: String,
  /** The label the file was given, `--to`'s. */
  val version: String,
  val url: String? = null,
  val size: Long,
  val sha256: String,
  val patches: List<ReleasePatch>
)

@Serializable
internal class ReleasePatch(
  /** The label of the release this patch starts from, `--from`'s. */
  val from: String,
  /** The file the patch applies to, so an updater can check it holds exactly that one. */
  val source: ReleaseSource,
  val name: String,
  val url: String? = null,
  val size: Long,
  val sha256: String,
  val patcher: String,
  /** The patch's format version, for an updater to hold against `Kiff.readableFormats`. */
  val format: Int
)

@Serializable
internal class ReleaseSource(val name: String, val size: Long, val sha256: String)

/** Reads and writes the manifest `kiff release` keeps, one entry per file. */
internal object ReleaseManifest {

  const val SCHEMA = 1

  const val FILE_NAME = "kiff-patches.json"

  /** The keys written here; any other a manifest holds is carried over as it is. */
  private val OWN_KEYS = setOf("schema", "generator", "files")

  @OptIn(ExperimentalSerializationApi::class)
  private val json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    // An absent url says there is none; a null one would only say the same thing louder.
    explicitNulls = false
  }

  /**
   * The manifest at [path], or null when there is none yet.
   *
   * A manifest this cannot merge into - not JSON, another schema, a file without a name - is
   * refused rather than replaced: it holds other files' entries, which would be lost.
   */
  fun read(path: String): JsonObject? {
    if (!KiffFiles.exists(path)) return null
    val root = try {
      json.parseToJsonElement(KiffFiles.readBytes(path).decodeToString())
    } catch (e: SerializationException) {
      throw refused(path, "it is not JSON (${e.message})")
    }
    if (root !is JsonObject) throw refused(path, "it is not a JSON object")
    val schema = root["schema"]
    if (schema !is JsonPrimitive || schema.isString || schema.intOrNull != SCHEMA) {
      throw refused(path, "its schema is ${schema ?: "missing"}, and this kiff writes $SCHEMA")
    }
    val files = root["files"] as? JsonArray ?: throw refused(path, "it has no files array")
    if (files.any { nameOf(it) == null }) throw refused(path, "a files entry has no name")
    return root
  }

  /**
   * [existing] with [file] in place of the entry of the same name, or after the others when there
   * is none, encoded the same way for the same entries every time.
   */
  fun merge(existing: JsonObject?, file: ReleaseFile, generator: String): String {
    val entry = json.encodeToJsonElement(ReleaseFile.serializer(), file)
    val files = mutableListOf<JsonElement>()
    var placed = false
    for (other in existing?.get("files") as? JsonArray ?: emptyList()) {
      when {
        nameOf(other) != file.name -> files += other
        // A second entry of the same name, which only a hand edit makes, goes with the first.
        !placed -> files += entry.also { placed = true }
      }
    }
    if (!placed) files += entry
    val root = buildJsonObject {
      put("schema", SCHEMA)
      put("generator", generator)
      put("files", JsonArray(files))
      existing?.forEach { (key, value) -> if (key !in OWN_KEYS) put(key, value) }
    }
    return json.encodeToString(JsonObject.serializer(), root) + "\n"
  }

  private fun nameOf(entry: JsonElement): String? =
    ((entry as? JsonObject)?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content

  private fun refused(path: String, why: String) =
    IOException("Cannot merge into $path: $why")
}
