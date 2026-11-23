package tgx.gradle.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import tgx.gradle.fatal
import tgx.gradle.validateDir
import java.io.File

@CacheableTask
abstract class PatchJetpackMediaTask : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val inputDirs: ConfigurableFileCollection

  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun patchJetpackMedia() {
    val output = validateDir(outputDir.get().asFile)
    if (!output.mkdirs() && !output.isDirectory) {
      fatal("mkdirs failed: ${output.absolutePath}")
    }

    val copiedFiles = mutableSetOf<String>()
    inputDirs.filter { input ->
      validateDir(input)
      input.isDirectory
    }.forEach { input ->
      input.resolve("include").walkTopDown().filter {
        it.isFile
      }.forEach { inputFile ->
        val outputFile = output.resolve(inputFile.relativeTo(input))
        if (!outputFile.parentFile.isDirectory && !outputFile.parentFile.mkdirs())
          fatal("mkdirs failed: ${outputFile.parentFile.absolutePath}")
        inputFile.copyTo(outputFile,
          overwrite = true
        )
        copiedFiles += inputFile.relativeTo(input).path
      }

      input.walkTopDown().onEnter {
        it.parentFile != input || it.name != "include"
      }.filter {
        it.isFile && it.name != "CMakeLists.txt" && it.extension != "sh" && it.extension != "mk"
      }.forEach { file ->
        require(file.isFile)
        val target = output.resolve(file.relativeTo(input))
        writeToFile(target) { out ->
          out.append(patchSourceCode(file))
        }
        copiedFiles += file.name
      }
    }

    var deletedFiles = false
    output.walkTopDown().filter {
      it.isFile
    }.forEach { file ->
      val relative = file.relativeTo(output)
      if (!copiedFiles.contains(relative.path)) {
        logger.lifecycle("Deleting rudimentary file: ${relative.path} / ${file.absolutePath}")
        if (!file.delete()) {
          error("Rudimentary file could not be deleted: ${file.absolutePath}")
        }
        deletedFiles = true
      }
    }
    if (deletedFiles) {
      logger.lifecycle("Copied files include:\n${copiedFiles.joinToString("\n")}")
    }
  }

  private fun patchFfmpegJni(source: String): String {
    // FFmpeg's AAC decoder keeps the channel configuration of the last decoded frames
    // after avcodec_flush_buffers(). If a file ends with frames that switch it to
    // LFE+LFE2, every frame decoded after a seek back (e.g. replay) is silent.
    // Recreate the context on reset for AAC, the same way upstream already does for TrueHD.
    val trueHdReset = "if (codecId == AV_CODEC_ID_TRUEHD) {"
    if (!source.contains(trueHdReset)) {
      fatal("ffmpeg_jni.cc: TrueHD reset branch not found")
    }
    return source.replace(trueHdReset, "if (codecId == AV_CODEC_ID_TRUEHD || codecId == AV_CODEC_ID_AAC) {")
  }

  private fun patchSourceCode(file: File): String {
    val fileName = file.nameWithoutExtension
    val source = file.readText().let {
      if (fileName == "ffmpeg_jni") patchFfmpegJni(it) else it
    }
    return source
      .replace(Regex("^#define LOG_TAG \"[^\"]+\"\n", RegexOption.MULTILINE), "")
      .replace(Regex("(?<=^#include <)android/(?=log.h)", RegexOption.MULTILINE), "")
      .replace(Regex("^jint JNI(?=_OnLoad\\s*\\(JavaVM\\s*\\*)", RegexOption.MULTILINE), "extern \"C\" jint ${fileName}")
      .replace(Regex("(?<=^extern \"C\" JNIEXPORT jint JNICALL )JNI(?=_OnLoad\\s*\\(JavaVM\\s*\\*)", RegexOption.MULTILINE), fileName)
      .replace(
        "__android_log_assert(NULL, LOG_TAG, ##",
        "loga(TAG_NDK, "
      )
      .replace(
        Regex("__android_log_print\\(ANDROID_LOG_(ERROR|WARNING|DEBUG|VERBOSE),\\s*LOG_TAG,")
      ) {
        val func = when (
          val level = it.groupValues[1]
        ) {
          "ERROR" -> "loge"
          "WARNING" -> "logw"
          "DEBUG" -> "logd"
          "VERBOSE" -> "logv"
          else -> error("Unknown level: $level")
        }
        "${func}(TAG_NDK,"
    }
  }
}