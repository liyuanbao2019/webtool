package com.gxcj.xjtool.service.impl;

import com.gxcj.xjtool.config.SecurityConfig;
import com.gxcj.xjtool.service.SqlSecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * SQL安全检查服务实现
 * 基于关键词黑名单检测危险SQL
 */
@Service
public class SqlSecurityServiceImpl implements SqlSecurityService {

    private static final Logger log = LoggerFactory.getLogger(SqlSecurityServiceImpl.class);

    @Autowired
    private SecurityConfig securityConfig;

    // 匹配单行注释 -- ...
    private static final Pattern SINGLE_LINE_COMMENT = Pattern.compile("--.*?(?=\\r?\\n|$)", Pattern.DOTALL);
    // 匹配多行注释 /* ... */
    private static final Pattern MULTI_LINE_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    // 合并多余空白（两个及以上空白替换为单个空格）
    private static final Pattern EXTRA_WHITESPACE = Pattern.compile("\\s{2,}");
    // 去掉字符串字面量（'...' 和 "..."），防止内容中的关键词干扰判断
    private static final Pattern STRING_LITERAL_SINGLE = Pattern.compile("'[^']*'");
    private static final Pattern STRING_LITERAL_DOUBLE = Pattern.compile("\"[^\"]*\"");

    @Override
    public SqlCheckResult checkSql(String sql, String username) {
        if (!securityConfig.getSqlCheck().isEnabled()) {
            return SqlCheckResult.safe();
        }

        if (sql == null || sql.trim().isEmpty()) {
            return SqlCheckResult.unsafe("SQL语句为空");
        }

        // 规范化SQL：去注释、去字符串字面量、合并空白
        String normalized = normalizeSql(sql);

        // 检查是否是管理员用户
        boolean isAdmin = securityConfig.getSqlCheck().getAdminUsers().contains(username);

        // 检查危险关键词
        for (String keyword : securityConfig.getSqlCheck().getBlockKeywords()) {
            String regex = "(?i)\\b" + Pattern.quote(keyword) + "\\b";
            if (Pattern.compile(regex).matcher(normalized).find()) {
                if (isAdmin) {
                    log.warn("管理员 {} 执行包含危险关键词 {} 的SQL，需二次确认", username, keyword);
                    return SqlCheckResult.needConfirm("包含危险关键词: " + keyword);
                } else {
                    log.warn("用户 {} 尝试执行包含危险关键词 {} 的SQL", username, keyword);
                    return SqlCheckResult.unsafe("非管理员权限无法执行包含危险关键词的SQL: " + keyword);
                }
            }
        }

        // 检查DELETE不带WHERE
        if (hasDeleteWithoutWhere(normalized, sql)) {
            if (isAdmin) {
                log.warn("管理员 {} 执行不带WHERE的DELETE，需二次确认", username);
                return SqlCheckResult.needConfirm("检测到全表删除操作(DELETE without WHERE)");
            } else {
                log.warn("用户 {} 尝试执行不带WHERE的DELETE", username);
                return SqlCheckResult.unsafe("非管理员权限无法执行不带WHERE条件的DELETE语句");
            }
        }

        // 检查UPDATE不带WHERE
        if (hasUpdateWithoutWhere(normalized, sql)) {
            if (isAdmin) {
                log.warn("管理员 {} 执行不带WHERE的UPDATE，需二次确认", username);
                return SqlCheckResult.needConfirm("检测到全表更新操作(UPDATE without WHERE)");
            } else {
                log.warn("用户 {} 尝试执行不带WHERE的UPDATE", username);
                return SqlCheckResult.unsafe("非管理员权限无法执行不带WHERE条件的UPDATE语句");
            }
        }

        return SqlCheckResult.safe();
    }

    /**
     * 规范化SQL：去除注释、字符串字面量，合并多余空白为单空格，便于精确分析
     */
    private String normalizeSql(String sql) {
        String s = sql;
        // 先去多行注释（需要先处理，避免嵌套）
        s = MULTI_LINE_COMMENT.matcher(s).replaceAll(" ");
        // 再去单行注释
        s = SINGLE_LINE_COMMENT.matcher(s).replaceAll(" ");
        // 去掉字符串字面量，防止内容中的关键词干扰
        s = STRING_LITERAL_SINGLE.matcher(s).replaceAll("'__STRING__'");
        s = STRING_LITERAL_DOUBLE.matcher(s).replaceAll("\"__STRING__\"");
        // 合并多余空白
        s = EXTRA_WHITESPACE.matcher(s).replaceAll(" ");
        return s.trim();
    }

    /**
     * 判断DELETE语句是否不带WHERE子句
     */
    private boolean hasDeleteWithoutWhere(String normalized, String original) {
        // 匹配 DELETE FROM table_name (后面不再有 WHERE)
        // 先在规范化SQL中定位 DELETE FROM <table>
        Pattern p = Pattern.compile("(?i)\\bDELETE\\s+FROM\\s+\\w+\\b");
        java.util.regex.Matcher m = p.matcher(normalized);
        if (!m.find()) return false;

        int deleteEnd = m.end();
        String afterDelete = normalized.substring(deleteEnd);
        // 如果 DELETE 之后还存在 WHERE（独立单词），则不是全表删除
        return !Pattern.compile("(?i)\\bWHERE\\b").matcher(afterDelete).find();
    }

    /**
     * 判断UPDATE语句是否不带WHERE子句
     */
    private boolean hasUpdateWithoutWhere(String normalized, String original) {
        // 匹配 UPDATE table_name SET
        Pattern p = Pattern.compile("(?i)\\bUPDATE\\s+\\w+\\s+SET\\b");
        java.util.regex.Matcher m = p.matcher(normalized);
        if (!m.find()) return false;

        int setEnd = m.end();
        String afterSet = normalized.substring(setEnd);
        // 如果 SET 之后还存在 WHERE（独立单词），则不是全表更新
        return !Pattern.compile("(?i)\\bWHERE\\b").matcher(afterSet).find();
    }
}
