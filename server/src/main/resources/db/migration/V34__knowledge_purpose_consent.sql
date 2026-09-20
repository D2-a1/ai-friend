-- 只扩展用途类型，不给既有用户插入任何 GRANTED 记录。
ALTER TABLE consent_record DROP CHECK chk_consent_type;
ALTER TABLE consent_record ADD CONSTRAINT chk_consent_type CHECK (
    type IN ('BASIC_IDENTITY', 'MICROPHONE', 'NOTIFICATION', 'ACCESSIBILITY',
             'VOICE_TEMPLATE', 'TASK_AUDIO', 'TEST_VOICE_COLLECTION',
             'VOICE_MODEL_TRAINING', 'PERSONAL_MEMORY',
             'KNOWLEDGE_MODEL', 'CONTACT_GRAPH')
);
