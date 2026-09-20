-- 同一语音会话内的重说/纠错幂等墓碑。
ALTER TABLE task_operation DROP CHECK chk_task_operation_type;
ALTER TABLE task_operation ADD CONSTRAINT chk_task_operation_type CHECK (
    operation_type IN ('SELECTION', 'REVISION', 'CONFIRMATION', 'CHANNEL_RESULT')
);