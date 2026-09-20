-- RC匿名控制账本。本轮仅代码与模拟测试，未连接数据库或执行迁移。
-- 不存用户/文档标识、查询、原文、异常详情；不删除现有知识表或幂等记录。
CREATE TABLE knowledge_cleanup_retry (
    singleton_id TINYINT NOT NULL PRIMARY KEY,
    revision BIGINT NOT NULL,
    failures INT NOT NULL,
    pending_since DATETIME(3) NULL,
    next_attempt DATETIME(3) NOT NULL,
    last_seen_at DATETIME(3) NOT NULL,
    lease_token BINARY(16) NULL,
    lease_until DATETIME(3) NULL,
    escalated BOOLEAN NOT NULL,
    first_failures BIGINT NOT NULL,
    first_acknowledged BIGINT NOT NULL,
    escalations BIGINT NOT NULL,
    escalation_acknowledged BIGINT NOT NULL,
    CONSTRAINT chk_cleanup_singleton CHECK (singleton_id=1 AND revision>=1),
    CONSTRAINT chk_cleanup_pending CHECK (
        (failures=0 AND pending_since IS NULL AND escalated=FALSE)
        OR (failures>0 AND first_failures>0 AND pending_since IS NOT NULL AND pending_since<=last_seen_at)),
    CONSTRAINT chk_cleanup_lease CHECK (
        (lease_token IS NULL AND lease_until IS NULL)
        OR (lease_token IS NOT NULL AND lease_until IS NOT NULL AND lease_until>=last_seen_at
            AND lease_until<=DATE_ADD(last_seen_at,INTERVAL 30 SECOND))),
    CONSTRAINT chk_cleanup_escalated CHECK (escalated IN (FALSE,TRUE)
        AND (escalated=FALSE OR (escalations>0 AND pending_since IS NOT NULL
            AND last_seen_at>=DATE_ADD(pending_since,INTERVAL 15 MINUTE)))),
    CONSTRAINT chk_cleanup_alerts CHECK (first_failures>=0 AND first_acknowledged>=0
        AND first_acknowledged<=first_failures AND escalations>=0 AND escalations<=first_failures
        AND escalation_acknowledged>=0 AND escalation_acknowledged<=escalations)
) ENGINE=InnoDB;

INSERT INTO knowledge_cleanup_retry (singleton_id,revision,failures,pending_since,next_attempt,last_seen_at,
    lease_token,lease_until,escalated,first_failures,first_acknowledged,escalations,escalation_acknowledged)
VALUES (1,1,0,NULL,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),NULL,NULL,FALSE,0,0,0,0);
