package com.aifriend.feature.guardian

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianWakeFailureLogTest {

    @Test
    fun failureLogContainsOnlyStableStageAndExceptionType() {
        val sensitiveMessage = "secret path /data/user/0/com.aifriend/no_backup/model"

        val line = guardianWakeFailureLogLine(
            GuardianWakeFailureStage.MODEL_INSTALL,
            IllegalStateException(sensitiveMessage),
        )

        assertEquals(
            "event=guardian_wake_failure stage=MODEL_INSTALL exception=IllegalStateException",
            line,
        )
        assertFalse(line.contains(sensitiveMessage))
        assertFalse(line.contains("/data/"))
    }

    @Test
    fun releaseRulesKeepVoskAndJnaRuntimeBindings() {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        val rulesFile = generateSequence(File(workingDirectory)) { directory ->
            directory.parentFile
        }.map { directory -> directory.resolve("app/proguard-rules.pro") }
            .firstOrNull(File::isFile)
            ?: error("Cannot locate app/proguard-rules.pro from test working directory")
        val normalizedRules = rulesFile.readText()
            .lineSequence()
            .map(String::trim)
            .filterNot { line -> line.startsWith("#") || line.isBlank() }
            .joinToString("\n")

        assertTrue(normalizedRules.contains("-keep class org.vosk.** { *; }"))
        assertTrue(normalizedRules.contains("-keep class com.sun.jna.** { *; }"))
        listOf(
            "java.awt.Component",
            "java.awt.GraphicsEnvironment",
            "java.awt.HeadlessException",
            "java.awt.Window",
        ).forEach { desktopOnlyType ->
            assertTrue(normalizedRules.contains("-dontwarn $desktopOnlyType"))
        }
    }
}
