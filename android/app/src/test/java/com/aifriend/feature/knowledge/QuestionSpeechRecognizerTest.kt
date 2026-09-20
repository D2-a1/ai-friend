package com.aifriend.feature.knowledge

import com.aifriend.core.audio.CapturedAudio
import com.aifriend.core.audio.WavPcmCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** 合成PCM与native端口替身；不使用真人录音、不声称识别准确率。 */
class QuestionSpeechRecognizerTest {
    @Test fun independentQuestionMergesSegmentsAndClearsOwnedAudio() = runTest {
        val f = Fixture(); f.decoder.parts += result("如 何 使用"); f.decoder.final = result("这个 功能")
        val audio = audio(); val text = f.recognizer.recognize(audio)
        assertEquals("如何使用这个功能", text.text); assertEquals("QuestionTranscript[redacted]", text.toString())
        assertTrue(f.decoder.closed); assertTrue(audio.wavBytes.all { it == 0.toByte() }); f.assertCleared()
    }
    @Test fun englishWordBoundariesArePreserved() = runTest {
        val f = Fixture(); f.decoder.final = result("how does RAG work")
        assertEquals("how does RAG work", f.recognizer.recognize(audio()).text)
    }
    @Test fun emptyRecognitionIsNoSpeechNotUnavailable() = runTest {
        val f = Fixture(); f.decoder.final = result("  ")
        fails(QuestionSpeechFailure.NO_SPEECH) { f.recognizer.recognize(audio()) }; assertTrue(f.decoder.closed)
    }
    @Test fun unknownTokenDoesNotBecomeQuestion() = runTest {
        val f = Fixture(); f.decoder.final = result("[unk]")
        fails(QuestionSpeechFailure.NO_SPEECH) { f.recognizer.recognize(audio()) }
    }
    @Test fun corruptHeaderRejectedBeforeNativeOpen() = runTest {
        val f = Fixture(); val audio = audio(); audio.wavBytes[0] = 0
        fails(QuestionSpeechFailure.INVALID_AUDIO) { f.recognizer.recognize(audio) }
        assertEquals(0, f.opens); assertTrue(audio.wavBytes.all { it == 0.toByte() })
    }
    @Test fun falseDurationAndOversizeAreRejectedBeforeNativeOpen() = runTest {
        val f = Fixture(); val original = audio()
        fails(QuestionSpeechFailure.INVALID_AUDIO) { f.recognizer.recognize(CapturedAudio(original.wavBytes, 999)) }
        fails(QuestionSpeechFailure.INVALID_AUDIO) { f.recognizer.recognize(CapturedAudio(ByteArray(960045), 30000)) }
        assertEquals(0, f.opens)
    }
    @Test fun byteRateAlignmentAndRiffLengthMustMatch() = runTest {
        listOf(4, 28, 32).forEach { position ->
            val f = Fixture(); val audio = audio(); audio.wavBytes[position] = (audio.wavBytes[position].toInt() xor 1).toByte()
            fails(QuestionSpeechFailure.INVALID_AUDIO) { f.recognizer.recognize(audio) }; assertEquals(0, f.opens)
        }
    }
    @Test fun modelFailureIsUnavailableAndStillConsumesWav() = runTest {
        val audio = audio(); val recognizer = DefaultQuestionSpeechRecognizer(QuestionDecoderFactory { throw UnsatisfiedLinkError("synthetic") })
        fails(QuestionSpeechFailure.UNAVAILABLE) { recognizer.recognize(audio) }
        assertTrue(audio.wavBytes.all { it == 0.toByte() })
    }
    @Test fun decodeFailureClosesModelAndZeroesFedChunks() = runTest {
        val f = Fixture(); f.decoder.fail = true; val audio = audio()
        fails(QuestionSpeechFailure.UNAVAILABLE) { f.recognizer.recognize(audio) }
        assertTrue(f.decoder.closed); f.assertCleared(); assertTrue(audio.wavBytes.all { it == 0.toByte() })
    }
    @Test fun cancellationAfterModelOpenClosesModelAndConsumesWav() = runTest {
        val f = Fixture(); val audio = audio()
        val recognizer = DefaultQuestionSpeechRecognizer(QuestionDecoderFactory {
            currentCoroutineContext().cancel(); f.decoder
        })
        val result = async { recognizer.recognize(audio) }
        try { result.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertTrue(f.decoder.closed); assertTrue(audio.wavBytes.all { it == 0.toByte() })
    }
    @Test fun cancellationBeforeDispatchStillConsumesWav() = runTest {
        val f = Fixture(); val audio = audio()
        val result = async {
            currentCoroutineContext().cancel(); f.recognizer.recognize(audio)
        }
        try { result.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(0, f.opens); assertTrue(audio.wavBytes.all { it == 0.toByte() })
    }
    @Test fun malformedNonStringOrMissingTextRejected() = runTest {
        listOf("{", "{}", "{\"text\":42}", "{\"text\":null}", "[]").forEach { invalid ->
            val f = Fixture(); f.decoder.final = invalid
            fails(QuestionSpeechFailure.INVALID_RESULT) { f.recognizer.recognize(audio()) }; assertTrue(f.decoder.closed)
        }
    }
    @Test fun controlAndBrokenSurrogateRejected() = runTest {
        listOf("\u0000", "\uD800", "\uDC00").forEach { invalid ->
            val f = Fixture(); f.decoder.final = result(invalid)
            fails(QuestionSpeechFailure.INVALID_RESULT) { f.recognizer.recognize(audio()) }
        }
    }
    @Test fun unicodeCodePointLimitNotUtf16Length() = runTest {
        val f = Fixture(); f.decoder.final = result("😀".repeat(500))
        assertEquals(1000, f.recognizer.recognize(audio()).text.length)
        f.decoder.final = result("😀".repeat(501))
        fails(QuestionSpeechFailure.TOO_LONG) { f.recognizer.recognize(audio()) }
    }
    @Test fun cumulativeSegmentsCannotBypassQuestionLimit() = runTest {
        val f = Fixture(); f.decoder.parts += result("问".repeat(300)); f.decoder.final = result("答".repeat(201))
        fails(QuestionSpeechFailure.TOO_LONG) { f.recognizer.recognize(audio()) }
    }
    @Test fun rawNativeResponseIsBoundedBeforeJsonParsing() = runTest {
        val f = Fixture(); f.decoder.final = " ".repeat(32769)
        fails(QuestionSpeechFailure.INVALID_RESULT) { f.recognizer.recognize(audio()) }
    }
    @Test fun concurrentCallsUseAndCloseDifferentDecoderInstances() = runTest {
        val decoders = java.util.Collections.synchronizedList(mutableListOf<Decoder>())
        val recognizer = DefaultQuestionSpeechRecognizer(QuestionDecoderFactory { Decoder().also { decoders += it } })
        awaitAll(async { recognizer.recognize(audio()) }, async { recognizer.recognize(audio()) })
        assertEquals(2, decoders.size); assertNotSame(decoders[0], decoders[1]); assertTrue(decoders.all { it.closed })
    }
    private class Fixture {
        val decoder = Decoder(); var opens = 0
        val recognizer = DefaultQuestionSpeechRecognizer(QuestionDecoderFactory { opens++; decoder })
        fun assertCleared() { assertTrue(decoder.chunks.isNotEmpty()); assertTrue(decoder.chunks.all { chunk -> chunk.all { it == 0.toShort() } }) }
    }
    private class Decoder : QuestionDecoder {
        var closed = false; var fail = false
        var final = result("如何使用")
        val parts = java.util.ArrayDeque<String>(); val chunks = mutableListOf<ShortArray>()
        override fun accept(samples: ShortArray): String? { chunks += samples; if (fail) throw IllegalStateException("synthetic"); return parts.poll() }
        override fun finish() = final
        override fun close() { closed = true }
    }
    companion object {
        private fun result(text: String) = buildJsonObject { put("text", text) }.toString()
        private fun audio(): CapturedAudio {
            val pcm = ByteArray(32000); val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
            repeat(16000) { buffer.putShort(123) }
            return CapturedAudio(WavPcmCodec.encodeMono16(pcm), 1000).also { pcm.fill(0) }
        }
        private suspend fun fails(expected: QuestionSpeechFailure, block: suspend () -> Unit) {
            try { block() } catch (e: QuestionSpeechException) { assertEquals(expected, e.failure); return }
            throw AssertionError("Expected question speech failure")
        }
    }
}
