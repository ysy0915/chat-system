package com.example.chat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动配置自检：应用就绪后校验关键配置，把"带病上线"问题暴露在启动日志里，
 * 而不是等到线上 401 / SQL 报错 / 功能静默失败才被发现。
 *
 * <p>本项目的三次线上事故都属此类，均可在启动时检出：
 * <ul>
 *   <li>LLM api_key 是占位符（${OPENAI_API_KEY}）→ 调用 401，前端永远"思考中"</li>
 *   <li>tree_hole_messages 表缺列（schema 漂移）→ Unknown column 'provider'</li>
 *   <li>cross-node 交换机未声明 → 每次广播刷 ERROR（已由 CrossNodeExchangeDefaults 修复）</li>
 * </ul></p>
 *
 * <p>只告警不阻断启动：问题打 ERROR 日志（可被告警系统采集），由运维决策是否处理，
 * 避免自检误判导致服务无法拉起。</p>
 */
@Component
public class StartupConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(StartupConfigValidator.class);

    /** 占位符特征：${XXX} 模板变量 / changeme / your-xxx */
    private static final String PLACEHOLDER_REGEX = "\\$\\{[^}]+\\}|changeme|your-[a-z0-9-]+";

    /** 关键环境变量：未配置 → WARN；占位符值 → ERROR */
    private static final Map<String, String> REQUIRED_ENV = Map.of(
            "DB_PASSWORD", "数据库密码",
            "RABBITMQ_PASSWORD", "RabbitMQ 密码",
            "JWT_SECRET", "JWT 签名密钥",
            "INTERNAL_API_TOKEN", "内部接口令牌（/internal/** 鉴权）"
    );

    /** tree_hole_messages 代码依赖但历史上发生过漂移的列 → 缺失时的修复 DDL */
    private static final Map<String, String> TREE_HOLE_REQUIRED_COLUMNS = new LinkedHashMap<>() {{
        put("provider", "VARCHAR(64)");
        put("model", "VARCHAR(64)");
        put("mood", "VARCHAR(32)");
        put("tokens", "INT");
    }};

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    public StartupConfigValidator(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        int errors = checkEnvVars();
        JdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc != null) {
            errors += checkLlmApiKeys(jdbc);
            errors += checkTreeHoleSchema(jdbc);
        }
        if (errors > 0) {
            log.error("[StartupCheck] 配置自检发现 {} 项问题，详见上方日志（ERROR 级别项将导致线上功能异常）", errors);
        } else {
            log.info("[StartupCheck] 配置自检通过");
        }
    }

    private int checkEnvVars() {
        int errors = 0;
        for (Map.Entry<String, String> e : REQUIRED_ENV.entrySet()) {
            String value = System.getenv(e.getKey());
            if (value == null || value.isBlank()) {
                log.warn("[StartupCheck] 环境变量 {}（{}）未配置", e.getKey(), e.getValue());
            } else if (value.matches("(?i).*(" + PLACEHOLDER_REGEX + ").*")) {
                log.error("[StartupCheck] 环境变量 {}（{}）仍是占位符值，相关功能将无法正常工作", e.getKey(), e.getValue());
                errors++;
            }
        }
        return errors;
    }

    /** 已启用供应商的 api_key 若为占位符（曾导致线上全员 401），启动即报 ERROR；明文存储报 WARN */
    private int checkLlmApiKeys(JdbcTemplate jdbc) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT c.provider_name, p.prop_value FROM llm_provider_props p "
                            + "JOIN llm_provider_config c ON c.id = p.provider_config_id "
                            + "WHERE p.prop_key = 'api_key' AND c.enabled = 1");
            int bad = 0;
            int plaintext = 0;
            for (Map<String, Object> row : rows) {
                Object v = row.get("prop_value");
                String value = v == null ? "" : v.toString();
                if (v == null || value.isBlank() || value.matches("(?i).*(" + PLACEHOLDER_REGEX + ").*")) {
                    log.error("[StartupCheck] 已启用的 LLM 供应商 [{}] 的 api_key 为空/占位符，该供应商所有调用将 401。"
                            + "修复：UPDATE llm_provider_props SET prop_value='<真实Key>' WHERE prop_key='api_key' AND provider_config_id=(对应id)",
                            row.get("provider_name"));
                    bad++;
                } else if (!value.startsWith("enc:v1:")) {
                    plaintext++;
                }
            }
            if (plaintext > 0 && envBlank("APP_MASTER_KEY")) {
                log.warn("[StartupCheck] {} 个已启用供应商的 api_key 仍为明文存储且未配置 APP_MASTER_KEY。"
                        + "配置后重启 chat-llm 会自动加密迁移（openssl rand -hex 32 生成，写入 /opt/app/.env）", plaintext);
            }
            return bad;
        } catch (Exception e) {
            // 本地库可能无这些表，跳过即可
            log.debug("[StartupCheck] LLM api_key 自检跳过: {}", e.getMessage());
            return 0;
        }
    }

    private boolean envBlank(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank();
    }

    /** 代码与 schema 漂移检测（tree_hole_messages 曾缺 provider 列导致 Unknown column 报错） */
    private int checkTreeHoleSchema(JdbcTemplate jdbc) {
        try {
            Integer tableExists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = DATABASE() AND table_name = 'tree_hole_messages'", Integer.class);
            if (tableExists == null || tableExists == 0) {
                return 0;
            }
            int missing = 0;
            for (Map.Entry<String, String> col : TREE_HOLE_REQUIRED_COLUMNS.entrySet()) {
                Integer cnt = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                                + "AND table_name = 'tree_hole_messages' AND column_name = ?",
                        Integer.class, col.getKey());
                if (cnt == null || cnt == 0) {
                    log.error("[StartupCheck] 表 tree_hole_messages 缺少列 {}（代码与 schema 漂移）。修复 DDL: "
                            + "ALTER TABLE tree_hole_messages ADD COLUMN {} {}",
                            col.getKey(), col.getKey(), col.getValue());
                    missing++;
                }
            }
            return missing;
        } catch (Exception e) {
            log.debug("[StartupCheck] tree_hole_messages 自检跳过: {}", e.getMessage());
            return 0;
        }
    }
}
