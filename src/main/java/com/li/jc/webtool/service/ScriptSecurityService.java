package com.li.jc.webtool.service;

import com.li.jc.webtool.dto.ScriptScanResult;
import com.li.jc.webtool.dto.ScriptScanResult.DangerousOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 脚本安全扫描服务
 * 对Shell脚本执行命令进行危险操作检测，支持：
 * - Shell脚本内容扫描（sh -c "..." 中的内联脚本）
 * - 脚本文件执行检测（sh/bash/./ 执行 .sh/.bash 文件）
 * - 数据库操作检测（mysql -e "..." / mysql < script.sql）
 * - 管道和重定向危险操作检测
 * - 压缩包危险解压操作检测
 *
 * @author 李金才 (li.jc)
 * @version 1.0.0-SNAPSHOT
 * @since 2026-04-10
 */
@Service
public class ScriptSecurityService {

    private static final Logger log = LoggerFactory.getLogger(ScriptSecurityService.class);

    @Autowired(required = false)
    private com.li.jc.webtool.config.SshSecurityConfig securityConfig;

    // 危险操作等级常量（与 SSH 危险命令一致，统一 high/medium/low 三级）
    private static final String RISK_NONE = "none";
    private static final String RISK_LOW = "low";
    private static final String RISK_MEDIUM = "medium";
    private static final String RISK_HIGH = "high";

    // 操作类型常量
    private static final String TYPE_DB = "database";
    private static final String TYPE_FILE = "file";
    private static final String TYPE_SYSTEM = "system";
    private static final String TYPE_PIPE = "pipe";
    private static final String TYPE_ARCHIVE = "archive";
    private static final String TYPE_NETWORK = "network";

    // ============================================================
    // 危险操作检测规则（除 dangerousCommands 配置化扫描外的补充规则）
    // ============================================================

    /**
     * 【高危】格式化磁盘/分区
     */
    private static final Pattern PATTERN_DISK_DESTROY = Pattern.compile(
            "(?i)\\b(mkfs\\s+-|dd\\s+.*of=/|fdisk\\s+-|parted\\s+-|blkid\\s+)"
    );

    /**
     * 【高危】关闭/重启系统
     */
    private static final Pattern PATTERN_SHUTDOWN = Pattern.compile(
            "(?i)\\b(shutdown|reboot|halt|poweroff|init\\s+['\"]?[016])\\b"
    );

    /**
     * 【高危】覆盖系统配置文件
     */
    private static final Pattern PATTERN_FILE_OVERWRITE = Pattern.compile(
            "(?i)(:\\s*>|>\\s*)/etc/|(:\\s*>|>\\s*)/usr/|(:\\s*>|>\\s*)/var/|(:\\s*>|>\\s*)/root/"
    );

    /**
     * 【高危】chmod 危险权限修改
     */
    private static final Pattern PATTERN_CHMOD_DANGEROUS = Pattern.compile(
            "(?i)\\bchmod\\s+(-R\\s+)?([0-7]{3,4}|[ugo]?\\+?[sSxX])\\b"
    );

    /**
     * 【高危】chown 修改系统目录所有者
     */
    private static final Pattern PATTERN_CHOWN_DANGEROUS = Pattern.compile(
            "(?i)\\bchown\\s+(-R\\s+)?(root|0:0|0\\s|\\d+:\\d+)\\s+(/|/etc|/usr|/var|/home)\\b"
    );

    /**
     * 【高危】heredoc 覆盖系统文件
     */
    private static final Pattern PATTERN_HEREDOC_OVERWRITE = Pattern.compile(
            "(?i)<<\\s*['\"]?\\w+['\"]?\\s*>\\s*(/|/etc/|/usr/|/var/|/root/)"
    );

    /**
     * 【高危】curl/wget 管道到 shell（最常见的远程入侵手法）
     */
    private static final Pattern PATTERN_CURL_PIPE_SH = Pattern.compile(
            "(?i)\\b(curl|wget)\\s+.*\\|\\s*(bash|sh|python|perl|php|ruby)"
    );

    /**
     * 【高危】curl/wget 下载到系统目录
     */
    private static final Pattern PATTERN_REMOTE_EXEC = Pattern.compile(
            "(?i)\\b(curl|wget)\\s+.*(-o\\s+|--output\\s+)(/tmp|/var/tmp|/dev|/bin|/sbin|/usr/bin)/"
    );

    /**
     * 【高危】SQL 命令行执行危险操作
     */
    private static final Pattern PATTERN_DB_EXEC = Pattern.compile(
            "(?i)\\b(mysql|psql|sqlplus|pg_dump|mysqldump|oracle)\\s+.*(-e\\s*[\"'].*?(DROP|TRUNCATE|DELETE|ALTER|CREATE\\s+DATABASE)\\b|-f\\s+|--execute\\s+)"
    );

    /**
     * 【高危】数据库备份还原管道
     */
    private static final Pattern PATTERN_DB_PIPE = Pattern.compile(
            "(?i)(mysqldump|pg_dump|pg_dumpall)\\s+.*\\|\\s*(mysql|psql|sqlplus)"
    );

    /**
     * 【高危】数据库 DROP 操作
     */
    private static final Pattern PATTERN_DB_DROP = Pattern.compile(
            "(?i)\\b(DROP\\s+(DATABASE|TABLE|INDEX|PROCEDURE|FUNCTION|VIEW|TRIGGER))\\b"
    );

    /**
     * 【高危】truncate 全表清空
     */
    private static final Pattern PATTERN_DB_TRUNCATE = Pattern.compile(
            "(?i)\\bTRUNCATE\\s+TABLE\\b"
    );

    /**
     * 【高危】DELETE 无 WHERE 条件
     */
    private static final Pattern PATTERN_DB_DELETE_ALL = Pattern.compile(
            "(?i)\\bDELETE\\s+FROM\\s+\\w+\\s*(?:--.*)?$",
            Pattern.MULTILINE
    );

    /**
     * 【高危】UPDATE 无 WHERE 条件（排除 SET ... WHERE ... 的写法）
     */
    private static final Pattern PATTERN_DB_UPDATE_ALL = Pattern.compile(
            "(?i)\\bUPDATE\\s+\\w+\\s+SET\\b(?!.*\\bWHERE\\b)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 【高危】systemctl 停止/禁用服务
     */
    private static final Pattern PATTERN_SYSTEMCTL = Pattern.compile(
            "(?i)\\bsystemctl\\s+(stop|disable|kill|mask)\\s+"
    );

    /**
     * 【中危】tar 解压到根目录
     */
    private static final Pattern PATTERN_TAR_DANGEROUS = Pattern.compile(
            "(?i)\\btar\\s+.*(-C\\s*/\\s|--strip-components)"
    );

    /**
     * 【中危】unzip 解压到根目录
     */
    private static final Pattern PATTERN_UNZIP_DANGEROUS = Pattern.compile(
            "(?i)\\bunzip\\s+.*-d\\s+/"
    );

    /**
     * 【中危】tee 管道到系统目录
     */
    private static final Pattern PATTERN_TEE_DANGEROUS = Pattern.compile(
            "(?i)\\btee\\s+.*(>|\\|)\\s*(/|/etc/|/usr/|/var/|/root/)"
    );

    /**
     * 【中危】eval/exec 动态执行
     */
    private static final Pattern PATTERN_EVAL_EXEC = Pattern.compile(
            "(?i)\\b(eval|exec)\\s+\\$\\("
    );

    /**
     * 【中危】管道到 shell
     */
    private static final Pattern PATTERN_PIPE_SHELL = Pattern.compile(
            "(?i)\\b(tee|exec)\\s+.*\\|\\s*(sh|bash|zsh)"
    );

    /**
     * 【中危】service 停止/重启服务
     */
    private static final Pattern PATTERN_SERVICE = Pattern.compile(
            "(?i)\\bservice\\s+(\\w+\\s+)?(stop|restart|reload)"
    );

    /**
     * 【中危】kill -9 强制终止进程
     */
    private static final Pattern PATTERN_KILL_DANGEROUS = Pattern.compile(
            "(?i)\\bkill\\s+(?:(?:-9|-15|-SIGKILL|-SIGTERM|-KILL|-TERM)\\s+|(?=[\"'$0-9]))[^\\n]+"
                    + "|\\bkillall\\s+(?:-9|-SIGKILL|-KILL)\\s+[^\\n]+"
    );

    // ============================================================
    // SQL 语句检测（用于脚本内容中的 SQL）
    // ============================================================

    /**
     * SQL注释注入检测（单行注释截断语句）
     */
    private static final Pattern PATTERN_SQL_INJECT = Pattern.compile(
            "(?i)--.*;(?:DROP|DELETE|TRUNCATE|UPDATE|INSERT|ALTER|CREATE|GRANT|REVOKE)"
    );

    /** Immutable, per-call rule state: Spring services may be scanned concurrently. */
    private static final class Rule {
        final Pattern pattern;
        final String type, subType, level, description, suggestion;

        Rule(String pattern, String type, String subType, String level, String description, String suggestion) {
            this.pattern = Pattern.compile(pattern);
            this.type = type;
            this.subType = subType;
            this.level = level;
            this.description = description;
            this.suggestion = suggestion;
        }
    }

    private List<DangerousOperation> matchRuleAll(ShellCommandScanner.Command command, Rule rule) {
        List<DangerousOperation> list = new ArrayList<>();
        if (rule.subType.equals("kill_dangerous") && command.isProcessProbe()) return list;
        boolean sql = TYPE_DB.equals(rule.type) && !rule.subType.equals("db_exec")
                && !rule.subType.equals("db_pipe");
        if (sql && !Arrays.asList("mysql", "psql", "sqlplus", "oracle", "DROP", "TRUNCATE", "DELETE", "UPDATE")
                .contains(command.name) && !Arrays.asList("drop", "truncate", "delete", "update").contains(command.name)) {
            return list;
        }
        boolean pipeline = Arrays.asList("db_pipe", "remote_exec", "pipe_shell", "tee_dangerous").contains(rule.subType);
        String content = pipeline ? command.pipelineText() : command.text;
        Matcher m = rule.pattern.matcher(content);
        while (m.find()) {
            boolean redirection = rule.subType.equals("overwrite_system") || rule.subType.equals("heredoc_overwrite");
            // Command rules may only match the executable word, never its data arguments.
            if (!sql && !redirection && (m.start() != 0 || m.end() < command.name.length())) continue;
            if (redirection && command.isQuoted(m.start())) continue;
            DangerousOperation op = new DangerousOperation(rule.type, rule.subType, sanitizeMatch(m.group()),
                    rule.description, rule.level, rule.suggestion);
            op.setDedupeKey(command.key + ":" + rule.type + ":" + rule.subType + ":" + m.start());
            list.add(op);
        }
        return list;
    }

    /**
     * 脱敏匹配文本（保留关键部分）
     */
    private String sanitizeMatch(String matched) {
        if (matched == null) return "";
        // 限制最大显示长度
        return matched.length() > 80 ? matched.substring(0, 80) + "..." : matched;
    }

    /**
     * 主扫描入口：扫描用户输入的命令文本
     * 支持完整的命令（如 "sh -c 'rm -rf /'" 或 "mysql -e 'drop table' db"）
     *
     * @param command 用户输入的完整命令文本
     * @return 扫描结果
     */
    public ScriptScanResult scanCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return ScriptScanResult.safe();
        }

        // 安全配置关闭时直接放行
        if (securityConfig == null || !securityConfig.isScriptScanEnabled()) {
            return ScriptScanResult.safe();
        }

        List<DangerousOperation> operations = new ArrayList<>();
        Set<String> seenDedupeKeys = new HashSet<>();
        String trimmed = command.trim();
        // Parse the original source once. Nested shell payloads are visited by the lexer.
        scanAllRules(trimmed, operations, seenDedupeKeys);

        // ========== 第4步：组装结果 ==========
        if (operations.isEmpty()) {
            return ScriptScanResult.safe();
        }

        // 计算综合危险等级（取所有操作中的最高等级）
        String maxRiskLevel = calculateMaxRiskLevel(operations);

        // 决定是否需要确认（high/medium 检测到危险操作时需确认）
        boolean needConfirm = isNeedConfirm(maxRiskLevel);

        String message = buildWarningMessage(operations);

        ScriptScanResult result = ScriptScanResult.needConfirm(operations, maxRiskLevel, message);
        if (!needConfirm) {
            // low 等级：允许执行，不弹确认框
            result.setPassed(true);
            result.setNeedConfirm(false);
        }

        log.info("脚本安全扫描结果: riskLevel={}, operations={}, needConfirm={}, commandPreview={}",
                maxRiskLevel, operations.size(), needConfirm,
                trimmed.length() > 60 ? trimmed.substring(0, 60) + "..." : trimmed);

        return result;
    }

    /**
     * 深度扫描脚本文件内容（通过 cat/more/head 等命令获取内容）
     * 当检测到 cat xxx.sh | sh 等模式时触发
     */
    public ScriptScanResult deepScanScriptContent(String scriptFilePath, String content) {
        if (content == null || content.trim().isEmpty()) {
            return ScriptScanResult.safe();
        }

        if (securityConfig == null || !securityConfig.isScriptScanEnabled()) {
            return ScriptScanResult.safe();
        }

        List<DangerousOperation> operations = new ArrayList<>();
        Set<String> seenDedupeKeys = new HashSet<>();

        // 深度扫描脚本内容
        scanAllRules(content, operations, seenDedupeKeys);

        if (operations.isEmpty()) {
            return ScriptScanResult.safe();
        }

        String maxRiskLevel = calculateMaxRiskLevel(operations);
        boolean needConfirm = isNeedConfirm(maxRiskLevel);

        String message = buildWarningMessage(operations);
        ScriptScanResult result = ScriptScanResult.needConfirm(operations, maxRiskLevel, message);
        if (!needConfirm) {
            // low 等级：允许执行，不弹确认框
            result.setPassed(true);
            result.setNeedConfirm(false);
        }

        log.warn("深度扫描脚本文件: {} 危险操作: {}个, 等级: {}", scriptFilePath, operations.size(), maxRiskLevel);

        return result;
    }

    /**
     * 动态扫描配置化危险命令
     * 根据 application.yml 中 audit.ssh.dangerous-commands 列表，在脚本内容中匹配所有出现，
     * 根据实际执行命令及参数生成风险提示，普通 rmdir 空目录清理为低危。
     * 与 SSH 终端执行命令使用同一配置列表，保证行为完全一致。
     */
    private void scanDangerousCommandsFromConfig(ShellCommandScanner.Command command,
            List<DangerousOperation> operations, Set<String> seenDedupeKeys) {
        if (securityConfig == null || securityConfig.getDangerousCommands() == null) return;
        for (String configured : securityConfig.getDangerousCommands()) {
            if (configured == null || !configured.trim().equalsIgnoreCase(command.name)) continue;
            String cmd = configured.trim().toLowerCase(Locale.ROOT);
            // The built-in rule covers these commands even when absent from the config.
            // Report each invocation once, rather than once for each rule source.
            if (PATTERN_SHUTDOWN.matcher(command.text).lookingAt()) continue;
            String level = RISK_HIGH;
            String subType = cmd + "_config";
            String description = buildDangerousCmdDescription(cmd, command.text);
            String suggestion = buildDangerousCmdSuggestion(cmd);
            if (cmd.equals("rmdir")) {
                boolean parents = command.hasParentDirectoryOption();
                level = parents ? RISK_MEDIUM : RISK_LOW;
                subType = parents ? "rmdir_parents" : "rmdir_empty";
                description = parents ? "删除目标空目录及其父空目录（rmdir -p）"
                        : "删除空目录（rmdir），不会递归删除目录中的文件";
                suggestion = parents ? "请确认父空目录是否也需要删除" : "请确认目标空目录是否可以删除";
            }
            String dedupeKey = command.key + ":config:" + cmd;
            if (seenDedupeKeys.add(dedupeKey)) {
                DangerousOperation op = new DangerousOperation(TYPE_FILE, subType, sanitizeMatch(command.text),
                        description, level, suggestion);
                op.setDedupeKey(dedupeKey);
                operations.add(op);
            }
        }
    }

    /**
     * 根据匹配文本和命令名，生成中文危险描述。
     * 对常见危险参数组合提供具体描述。
     */
    private String buildDangerousCmdDescription(String cmd, String matched) {
        String lower = matched.toLowerCase();
        switch (cmd.toLowerCase()) {
            case "rm":
                if (lower.contains("-rf") || lower.contains("-r") || lower.contains("-f")) {
                    return "检测到 rm 强制递归删除命令（rm -rf），将强制删除目标目录及所有子目录内容";
                }
                return "检测到危险删除命令 rm，可能导致文件或目录被永久删除";
            case "rmdir":
                if (lower.contains("-p")) {
                    return "检测到 rmdir 递归删除目录命令（rmdir -p），将删除目标目录及其父空目录";
                }
                return "检测到危险目录删除命令 rmdir，可能导致目录被永久删除";
            case "dd":
                return "检测到 dd 数据转换/复制命令，可能直接覆盖磁盘或分区，数据将不可恢复";
            case "mkfs":
                return "检测到 mkfs 格式化命令，将清空目标磁盘或分区的所有数据";
            case "fdisk":
                return "检测到 fdisk 分区管理命令，可能删除或修改磁盘分区，导致数据丢失";
            case "parted":
                return "检测到 parted 磁盘分区命令，可能删除或修改分区，导致数据丢失";
            case "shutdown":
                return "检测到 shutdown 关机命令，将立即关闭系统，中断所有运行中的服务";
            case "reboot":
                return "检测到 reboot 重启命令，将立即重启系统，中断所有运行中的服务";
            case "halt":
                return "检测到 halt 停机命令，将立即停止系统运行";
            case "poweroff":
                return "检测到 poweroff 关机命令，将立即关闭系统电源";
            case "init":
                return "检测到 init 系统控制命令，可能导致系统切换运行级别或关机";
            default:
                return "检测到危险命令 [" + cmd + "]，可能造成数据丢失或系统中断";
        }
    }

    /**
     * 根据命令名生成处置建议。
     */
    private String buildDangerousCmdSuggestion(String cmd) {
        switch (cmd.toLowerCase()) {
            case "rm":
            case "rmdir":
                return "请确认删除目标是否正确，rm/rmdir 操作不可逆，建议先备份重要数据";
            case "dd":
            case "mkfs":
            case "fdisk":
            case "parted":
                return "严重警告：磁盘操作不可逆！请绝对确认目标设备和脚本来源";
            case "shutdown":
            case "reboot":
            case "halt":
            case "poweroff":
            case "init":
                return "请确认是否需要关闭或重启服务器，确保已保存所有重要数据";
            default:
                return "请确认命令来源和执行必要性，避免造成不必要的数据损失或服务中断";
        }
    }

    /**
     * 执行所有危险操作规则扫描（每种规则在内容中可出现多次，分别计入列表）
     */
    private void scanAllRules(String content, List<DangerousOperation> operations, Set<String> seenDedupeKeys) {
        for (ShellCommandScanner.Command command : ShellCommandScanner.scan(content)) {
            scanCommandRules(command, operations, seenDedupeKeys);
        }
    }

    private void scanCommandRules(ShellCommandScanner.Command command, List<DangerousOperation> operations,
            Set<String> seenDedupeKeys) {
        scanDangerousCommandsFromConfig(command, operations, seenDedupeKeys);
        Rule rule;

        // ========== 数据库类危险操作 ==========

        // 【高危】数据库 DROP 操作
        rule = new Rule(PATTERN_DB_DROP.pattern(), TYPE_DB, "drop",
                RISK_HIGH,
                "检测到数据库 DROP 操作（删除数据库/表/索引等），数据将不可恢复",
                "请确认是否必须执行，如必须执行请备份数据后操作");
        addAllMatches(operations, seenDedupeKeys, matchRuleAll(command, rule));

        // 【高危】数据库 TRUNCATE 操作
        rule = new Rule(PATTERN_DB_TRUNCATE.pattern(), TYPE_DB, "truncate",
                RISK_HIGH,
                "检测到 TRUNCATE TABLE 操作，数据将被清空且不可恢复",
                "请确认是否必须执行，TRUNCATE 无法回滚");
        addAllMatches(operations, seenDedupeKeys, matchRuleAll(command, rule));

        // 【高危】DELETE 全表删除
        rule = new Rule(PATTERN_DB_DELETE_ALL.pattern(), TYPE_DB, "delete_all",
                RISK_HIGH,
                "检测到不带 WHERE 条件的 DELETE 语句，将清空整表数据",
                "请添加 WHERE 条件或确认是否必须执行全表删除");
        addAllMatches(operations, seenDedupeKeys, matchRuleAll(command, rule));

        // 【高危】UPDATE 全表更新
        rule = new Rule(PATTERN_DB_UPDATE_ALL.pattern(), TYPE_DB, "update_all",
                RISK_HIGH,
                "检测到不带 WHERE 条件的 UPDATE 语句，将更新整表数据",
                "请添加 WHERE 条件或确认是否必须执行全表更新");
        addAllMatches(operations, seenDedupeKeys, matchRuleAll(command, rule));

        // 【高危】SQL 命令行执行
        rule = new Rule(PATTERN_DB_EXEC.pattern(), TYPE_DB, "db_exec",
                RISK_HIGH,
                "检测到数据库命令行直接执行危险 SQL（DROP/TRUNCATE 等），数据面临不可恢复风险",
                "请使用数据库客户端工具并获得 DBA 授权后执行");
        addAllMatches(operations, seenDedupeKeys, matchRuleAll(command, rule));

        // 【高危】数据库导出还原管道
        rule = new Rule(PATTERN_DB_PIPE.pattern(), TYPE_DB, "db_pipe",
                RISK_HIGH,
                "检测到数据库备份还原管道操作，可能导致当前数据被覆盖",
                "请确认是否要覆盖当前数据库");
        addAllMatches(operations, seenDedupeKeys, matchRuleAll(command, rule));

        // ========== 文件系统类危险操作 ==========

        // 【高危】覆盖系统文件
        rule = new Rule(PATTERN_FILE_OVERWRITE.pattern(), TYPE_FILE, "overwrite_system",
                RISK_HIGH,
                "检测到直接覆盖系统配置文件操作（如 /etc/passwd）",
                "此操作可能导致系统无法登录或服务异常");
        addAllMatches(operations, seenDedupeKeys, matchRuleAll(command, rule));

        // 【高危】chmod 危险权限
        rule = new Rule(PATTERN_CHMOD_DANGEROUS.pattern(), TYPE_FILE, "chmod_dangerous",
                RISK_HIGH,
                "检测到 chmod 修改系统目录权限操作，可能导致安全风险",
                "请确认权限修改的必要性");
        addAllMatches(operations, seenDedupeKeys, matchRuleAll(command, rule));

        // 【高危】chown 危险操作
        rule = new Rule(PATTERN_CHOWN_DANGEROUS.pattern(), TYPE_FILE, "chown_dangerous",
                RISK_HIGH,
                "检测到 chown 修改系统目录所有者操作",
                "请确认所有者修改的必要性");
        addAllMatches(operations, seenDedupeKeys, matchRuleAll(command, rule));


        // ========== 系统类危险操作 ==========

        // 【高危】磁盘破坏
        rule = new Rule(PATTERN_DISK_DESTROY.pattern(), TYPE_SYSTEM, "disk_destroy",
                RISK_HIGH,
                "检测到磁盘/分区格式化或破坏操作，将导致数据永久丢失",
                "严重警告：此操作不可逆！请绝对确认脚本来源和执行必要性");
        addAllMatches(operations, seenDedupeKeys, matchRuleAll(command, rule));

        // 【高危】系统关机/重启
        rule = new Rule(PATTERN_SHUTDOWN.pattern(), TYPE_SYSTEM, "shutdown",
                RISK_HIGH,
                "检测到系统关机/重启命令，将中断所有服务",
                "请确认是否需要关闭/重启服务器");
        addAllMatches(operations, seenDedupeKeys, matchRuleAll(command, rule));

        // 【高危】systemctl 危险操作
        rule = new Rule(PATTERN_SYSTEMCTL.pattern(), TYPE_SYSTEM, "systemctl",
                RISK_HIGH,
                "检测到 systemctl 停止/禁用系统服务操作",
                "请确认是否要停止系统关键服务");
        addAllMatches(operations, seenDedupeKeys, matchRuleAll(command, rule));

        // 【中危】service 危险操作
        rule = new Rule(PATTERN_SERVICE.pattern(), TYPE_SYSTEM, "service",
                RISK_MEDIUM,
                "检测到 service 命令停止/重启服务操作",
                "请确认服务停止的影响范围");
        addAllMatches(operations, seenDedupeKeys, matchRuleAll(command, rule));

        // 【中危】kill 危险信号
        rule = new Rule(PATTERN_KILL_DANGEROUS.pattern(), TYPE_SYSTEM, "kill_dangerous",
                RISK_MEDIUM,
                "检测到终止进程操作（kill/killall）",
                "请确认目标进程，避免误杀重要服务进程");
        addAllMatches(operations, seenDedupeKeys, matchRuleAll(command, rule));

        // ========== 管道/远程执行类 ==========

        // 【高危】curl/wget 管道到 shell 执行
        rule = new Rule(PATTERN_CURL_PIPE_SH.pattern(), TYPE_NETWORK, "remote_exec",
                RISK_HIGH,
                "检测到从远程下载脚本并直接执行！这是最常见的远程入侵手法",
                "严重警告：不要执行来源不明的远程脚本！");
        addAllMatches(operations, seenDedupeKeys, matchRuleAll(command, rule));

        // 【高危】远程下载到系统目录
        rule = new Rule(PATTERN_REMOTE_EXEC.pattern(), TYPE_NETWORK, "remote_download",
                RISK_HIGH,
                "检测到远程下载文件到系统目录操作",
                "请确认下载来源的可信性");
        addAllMatches(operations, seenDedupeKeys, matchRuleAll(command, rule));

        // 【中危】eval/exec 动态执行
        rule = new Rule(PATTERN_EVAL_EXEC.pattern(), TYPE_PIPE, "eval_exec",
                RISK_MEDIUM,
                "检测到 eval/exec 动态命令执行，可能存在命令注入风险",
                "请确认脚本来源，避免命令注入攻击");
        addAllMatches(operations, seenDedupeKeys, matchRuleAll(command, rule));

        // 【中危】管道到 shell
        rule = new Rule(PATTERN_PIPE_SHELL.pattern(), TYPE_PIPE, "pipe_shell",
                RISK_MEDIUM,
                "检测到管道到 shell 执行操作",
                "请确认管道命令的安全性");
        addAllMatches(operations, seenDedupeKeys, matchRuleAll(command, rule));

        // 【高危】heredoc 覆盖系统文件
        rule = new Rule(PATTERN_HEREDOC_OVERWRITE.pattern(), TYPE_FILE, "heredoc_overwrite",
                RISK_HIGH,
                "检测到 heredoc 重定向覆盖系统文件操作",
                "请确认重定向目标，避免覆盖系统配置文件");
        addAllMatches(operations, seenDedupeKeys, matchRuleAll(command, rule));

        // 【中危】tee 管道危险操作
        rule = new Rule(PATTERN_TEE_DANGEROUS.pattern(), TYPE_PIPE, "tee_dangerous",
                RISK_MEDIUM,
                "检测到 tee 管道重定向到系统目录操作",
                "请确认重定向目标路径");
        addAllMatches(operations, seenDedupeKeys, matchRuleAll(command, rule));

        // ========== 压缩包类 ==========

        // 【中危】tar 危险解压
        rule = new Rule(PATTERN_TAR_DANGEROUS.pattern(), TYPE_ARCHIVE, "tar_dangerous",
                RISK_MEDIUM,
                "检测到 tar 解压到根目录操作，可能覆盖系统文件",
                "请确认解压目标路径");
        addAllMatches(operations, seenDedupeKeys, matchRuleAll(command, rule));

        // 【中危】unzip 危险解压
        rule = new Rule(PATTERN_UNZIP_DANGEROUS.pattern(), TYPE_ARCHIVE, "unzip_dangerous",
                RISK_MEDIUM,
                "检测到 unzip 解压到根目录操作，可能覆盖系统文件",
                "请确认解压目标路径");
        addAllMatches(operations, seenDedupeKeys, matchRuleAll(command, rule));

        // ========== SQL 注入检测 ==========
        rule = new Rule(PATTERN_SQL_INJECT.pattern(), TYPE_DB, "sql_inject",
                RISK_HIGH,
                "检测到 SQL 注释注入攻击模式",
                "请检查脚本中的 SQL 语句是否正确");
        addAllMatches(operations, seenDedupeKeys, matchRuleAll(command, rule));
    }

    /**
     * 按来源命令的位置与规则去重，同一脚本内分别执行的命令保留独立记录。
     */
    private void addAllMatches(List<DangerousOperation> operations, Set<String> seenDedupeKeys,
            List<DangerousOperation> found) {
        if (found == null || found.isEmpty()) {
            return;
        }
        for (DangerousOperation op : found) {
            if (op == null || op.getDedupeKey() == null) {
                continue;
            }
            if (!seenDedupeKeys.contains(op.getDedupeKey())) {
                seenDedupeKeys.add(op.getDedupeKey());
                operations.add(op);
            }
        }
    }

    /**
     * 计算综合危险等级（取最高等级）
     */
    private String calculateMaxRiskLevel(List<DangerousOperation> operations) {
        String max = RISK_NONE;
        for (DangerousOperation op : operations) {
            if (compareRiskLevel(op.getRiskLevel(), max) > 0) {
                max = op.getRiskLevel();
            }
        }
        return max;
    }

    /**
     * 比较危险等级（high > medium > low）
     */
    private int compareRiskLevel(String level1, String level2) {
        Map<String, Integer> order = new LinkedHashMap<>();
        order.put(RISK_NONE, 0);
        order.put(RISK_LOW, 1);
        order.put(RISK_MEDIUM, 2);
        order.put(RISK_HIGH, 3);
        return order.getOrDefault(level1, 0).compareTo(order.getOrDefault(level2, 0));
    }

    /**
     * 判断是否需要用户确认（high/medium 均需确认，阻断由 blockCriticalScriptOps 配置决定）
     */
    private boolean isNeedConfirm(String riskLevel) {
        return !RISK_NONE.equals(riskLevel) && !RISK_LOW.equals(riskLevel);
    }

    /**
     * 构建警告消息
     */
    private String buildWarningMessage(List<DangerousOperation> operations) {
        if (operations.isEmpty()) {
            return "脚本包含危险操作，请确认是否继续";
        }
        StringBuilder sb = new StringBuilder("检测到 ");
        sb.append(operations.size()).append(" 个危险操作：\n");
        for (int i = 0; i < Math.min(operations.size(), 5); i++) {
            DangerousOperation op = operations.get(i);
            sb.append("  ").append(i + 1).append(". [").append(op.getRiskLevel().toUpperCase()).append("] ");
            sb.append(op.getDescription());
            sb.append("\n");
        }
        if (operations.size() > 5) {
            sb.append("  ... 还有 ").append(operations.size() - 5).append(" 个危险操作");
        }
        return sb.toString();
    }

    /**
     * 获取危险等级对应的中文标签（与 SSH 危险命令一致）
     */
    public static String getRiskLevelLabel(String riskLevel) {
        switch (riskLevel) {
            case RISK_NONE: return "安全";
            case RISK_LOW: return "低危";
            case RISK_MEDIUM: return "中危";
            case RISK_HIGH: return "高危";
            default: return "未知";
        }
    }

    /**
     * 获取危险等级对应的颜色（与 SSH 危险命令一致）
     */
    public static String getRiskLevelColor(String riskLevel) {
        switch (riskLevel) {
            case RISK_NONE: return "#52c41a";
            case RISK_LOW: return "#1890ff";
            case RISK_MEDIUM: return "#faad14";
            case RISK_HIGH: return "#ff4d4f";
            default: return "#999999";
        }
    }
}
