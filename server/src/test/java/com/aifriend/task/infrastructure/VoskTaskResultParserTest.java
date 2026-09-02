package com.aifriend.task.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.task.application.TaskAsrSource;
import com.aifriend.task.application.TaskTranscriptCandidate;

class VoskTaskResultParserTest {

    private final VoskTaskResultParser parser = new VoskTaskResultParser();

    @Test
    void shouldParseBoundedAlternativesWithWordTimestamps() {
        String json = """
                {
                  "alternatives": [
                    {
                      "text": "叫 二狗子 回来",
                      "result": [
                        {"word":"叫","start":0.10,"end":0.20,"conf":0.90},
                        {"word":"二狗子","start":0.20,"end":0.60,"conf":0.80},
                        {"word":"回来","start":0.62,"end":0.90,"conf":0.70}
                      ]
                    },
                    {
                      "text": "叫 二狗 回来",
                      "result": [
                        {"word":"叫","start":0.10,"end":0.20,"conf":0.80},
                        {"word":"二狗","start":0.20,"end":0.60,"conf":0.60},
                        {"word":"回来","start":0.62,"end":0.90,"conf":0.70}
                      ]
                    }
                  ]
                }
                """;

        List<TaskTranscriptCandidate> candidates = parser.parse(
                json, TaskAsrSource.PRIMARY, "primary-v1");

        assertEquals(2, candidates.size());
        assertEquals(100, candidates.get(0).words().get(0).startMs());
        assertEquals(900, candidates.get(0).words().get(2).endMs());
        assertEquals(0.80D, candidates.get(0).confidence(), 0.0001D);
    }

    @Test
    void shouldReturnEmptyForSilenceResult() {
        assertTrue(parser.parse("{\"text\":\"\",\"result\":[]}",
                TaskAsrSource.PRIMARY, "primary-v1").isEmpty());
    }

    @Test
    void shouldFailClosedWhenTimestampOverflowsMilliseconds() {
        String json = """
                {"text":"回来","result":[
                  {"word":"回来","start":999999999999.0,
                   "end":1000000000000.0,"conf":0.8}
                ]}
                """;

        BusinessException exception = assertThrows(BusinessException.class,
                () -> parser.parse(json, TaskAsrSource.PRIMARY, "primary-v1"));

        assertEquals(ErrorCode.AUDIO_SEGMENT_UNCERTAIN, exception.errorCode());
    }
}
