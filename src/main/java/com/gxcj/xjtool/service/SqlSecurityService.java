package com.gxcj.xjtool.service;

/**
 * SQL安全检查服务接口
 * 检测危险SQL并提供管理员白名单支持
 */
public interface SqlSecurityService {

    /**
     * 检查SQL是否安全
     * 
     * @param sql      SQL语句
     * @param username 执行用户
     * @return 检查结果
     */
    SqlCheckResult checkSql(String sql, String username);

    /**
     * SQL检查结果
     */
    class SqlCheckResult {
        private boolean safe;
        private boolean needConfirm;
        private String reason;

        public SqlCheckResult(boolean safe, String reason) {
            this.safe = safe;
            this.reason = reason;
        }

        public boolean isSafe() {
            return safe;
        }

        public boolean isNeedConfirm() {
            return needConfirm;
        }

        public String getReason() {
            return reason;
        }

        public static SqlCheckResult safe() {
            return new SqlCheckResult(true, null);
        }

        public static SqlCheckResult unsafe(String reason) {
            return new SqlCheckResult(false, reason);
        }

        public static SqlCheckResult needConfirm(String reason) {
            SqlCheckResult result = new SqlCheckResult(false, reason);
            result.needConfirm = true;
            return result;
        }
    }
}
