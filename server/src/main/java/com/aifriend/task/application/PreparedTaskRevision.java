package com.aifriend.task.application;

import java.util.List;
import java.util.UUID;

import com.aifriend.task.domain.TaskRevisionMode;
import com.aifriend.task.domain.TaskState;
import com.aifriend.voice.application.ValidatedAudioObject;

/** 事务外完成识别、上下文补丁和声学匹配后的会话修订快照。 */
record PreparedTaskRevision(
        TaskStoredSession baseSession,
        ValidatedAudioObject audio,
        TaskClientContext context,
        TaskUnderstandingView understanding,
        List<TaskContactCandidate> matches,
        TaskMatchedContactView selectedContact,
        UUID selectedContactId,
        boolean replaceCandidates,
        boolean replaceSourceAudio,
        TaskState state,
        String spokenSummary,
        String summaryHash,
        TaskRevisionMode mode,
        String revisionTranscript) {
}