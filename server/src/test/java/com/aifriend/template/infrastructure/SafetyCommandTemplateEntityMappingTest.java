package com.aifriend.template.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.persistence.Column;

import org.junit.jupiter.api.Test;

/**
 * 安全指令模板生成列映射回归测试。
 *
 * <p>V12 使用 MySQL TINYINT 生成列维护 ACTIVE 模板唯一槽位；实体必须使用 Byte 只读映射，
 * 否则 Hibernate 在真实 MySQL 8.4 启动校验时会拒绝创建 SessionFactory。
 *
 * @author Codex
 * @since 1.0.0
 */
class SafetyCommandTemplateEntityMappingTest {

    @Test
    void shouldMapActiveSlotToMysqlTinyintAsReadOnlyByte() throws Exception {
        String migrationSql = Files.readString(Path.of(
                "src/main/resources/db/migration/V12__safety_command_template.sql"));
        assertThat(migrationSql).contains("active_slot TINYINT GENERATED ALWAYS AS");

        var activeSlotField = SafetyCommandTemplateEntity.class.getDeclaredField("activeSlot");
        assertThat(activeSlotField.getType()).isEqualTo(Byte.class);

        Column column = activeSlotField.getAnnotation(Column.class);
        assertThat(column).isNotNull();
        assertThat(column.name()).isEqualTo("active_slot");
        assertThat(column.insertable()).isFalse();
        assertThat(column.updatable()).isFalse();
    }
}
