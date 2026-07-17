package com.gxcj.xjtool.service;

import com.gxcj.xjtool.dto.ExecuteSqlRequest;
import com.gxcj.xjtool.dto.OracleDataSourceDto;
import com.gxcj.xjtool.dto.ResultEditCommitRequest;
import com.gxcj.xjtool.dto.SqlResultResponse;

import java.util.List;
import java.util.Map;

/**
 * Oracle 数据库服务接口
 */
public interface OracleService {

    /**
     * 获取配置的数据源列表
     */
    List<OracleDataSourceDto> getDataSources();

    /**
     * 获取可管理的数据源列表，包含完整配置（已加密传输）。
     */
    List<OracleDataSourceDto> getManageDataSources();

    /**
     * 新增数据源配置。
     */
    SqlResultResponse addDataSource(com.gxcj.xjtool.config.OracleConfig.OracleDataSource request);

    /**
     * 更新数据源配置。
     */
    SqlResultResponse updateDataSource(int datasourceIndex, com.gxcj.xjtool.config.OracleConfig.OracleDataSource request);

    /**
     * 删除数据源配置。
     */
    SqlResultResponse deleteDataSource(int datasourceIndex);

    /**
     * 测试数据库连接
     * 
     * @param datasourceIndex 数据源索引
     * @return 连接结果
     */
    SqlResultResponse testConnection(int datasourceIndex);

    /**
     * 获取数据源类型
     * 
     * @param datasourceIndex 数据源索引
     * @return 数据库类型 (ORACLE/DM/MYSQL)
     */
    String getDatasourceType(int datasourceIndex);

    /**
     * 执行 SQL 语句
     * 
     * @param request 执行请求
     * @return 执行结果
     */
    SqlResultResponse executeSql(ExecuteSqlRequest request);

    /**
     * Cancel a running SQL execution owned by the current HTTP session.
     */
    boolean cancelQuery(String queryId, String sessionId);

    /**
     * Commit pending result-grid cell edits in one transaction.
     */
    SqlResultResponse commitResultEdits(ResultEditCommitRequest request);

    /**
     * 获取 SQL 执行计划
     * 
     * @param request 执行请求
     * @return 执行计划结果
     */
    SqlResultResponse explainSql(ExecuteSqlRequest request);

    /**
     * 获取数据库对象列表
     * 
     * @param type            对象类型：tables, views, indexes, procedures, sequences
     * @param datasourceIndex 数据源索引
     * @return 对象列表
     */
    List<Map<String, String>> getDatabaseObjects(String type, int datasourceIndex);

    /**
     * 获取数据库对象的DDL语句
     * 
     * @param type            对象类型：views, indexes, procedures, sequences
     * @param name            对象名称
     * @param datasourceIndex 数据源索引
     * @return DDL语句
     */
    String getObjectDDL(String type, String name, int datasourceIndex);

    /**
     * 清除指定数据源的对象缓存
     * 
     * @param datasourceIndex 数据源索引
     */
    void clearObjectsCache(int datasourceIndex);

    /**
     * 获取表结构信息
     * 
     * @param tableName       表名
     * @param datasourceIndex 数据源索引
     * @return 表结构列表
     */
    List<Map<String, Object>> getTableStructure(String tableName, int datasourceIndex);

    /**
     * 获取表索引信息
     * 
     * @param tableName       表名
     * @param datasourceIndex 数据源索引
     * @return 索引列表
     */
    List<Map<String, Object>> getTableIndexes(String tableName, int datasourceIndex);

    /**
     * 生成建表SQL语句
     * 
     * @param tableName       表名
     * @param datasourceIndex 数据源索引
     * @return 建表SQL语句
     */
    String getCreateTableSQL(String tableName, int datasourceIndex);

    /**
     * 获取表分区信息
     * 
     * @param tableName       表名
     * @param datasourceIndex 数据源索引
     * @return 分区信息列表
     */
    List<Map<String, Object>> getTablePartitions(String tableName, int datasourceIndex);

    /**
     * 获取表触发器信息
     * 
     * @param tableName       表名
     * @param datasourceIndex 数据源索引
     * @return 触发器信息列表
     */
    List<Map<String, Object>> getTableTriggers(String tableName, int datasourceIndex);

    /**
     * 查询锁表信息（主节点 + 所有 slave 并行，去重合并）
     * 仅 Oracle 数据源有效
     *
     * @param datasourceIndex 数据源索引
     * @return 锁表记录列表
     */
    List<Map<String, Object>> getLockedObjects(int datasourceIndex);

    /**
     * 解锁指定会话
     *
     * @param sid             会话 SID
     * @param serial          会话 Serial#
     * @param datasourceIndex 数据源索引
     * @return 操作结果
     */
    Map<String, Object> unlockSession(String sid, String serial, int datasourceIndex);

    /**
     * Query MySQL wsrep-related running processes for the selected database.
     *
     * @param datasourceIndex datasource index
     * @param databaseName    database name
     * @param command        filter by COMMAND column (e.g. "Query", "Sleep"), null means no filter
     * @param eventType      event type: "all" means no STATE filter, "cluster_wait" means STATE LIKE '%wsrep:%'
     * @return process list rows
     */
    List<Map<String, Object>> getMysqlWsrepProcesses(int datasourceIndex, String databaseName, String command, String eventType);

    /**
     * Query currently running slow MySQL statements.
     *
     * @param datasourceIndex datasource index
     * @param databaseName    database name
     * @param minSeconds      minimum running seconds
     * @return slow SQL rows
     */
    List<Map<String, Object>> getMysqlCurrentSlowSql(int datasourceIndex, String databaseName, int minSeconds);

    /**
     * Query MySQL long transactions and lock waits.
     *
     * @param datasourceIndex datasource index
     * @param databaseName    database name
     * @param minSeconds      minimum transaction seconds
     * @return diagnostics result with longTransactions and lockWaits
     */
    Map<String, Object> getMysqlTransactionDiagnostics(int datasourceIndex, String databaseName, int minSeconds);

    /**
     * Build a guarded online DDL plan for MySQL/PXC.
     *
     * @param datasourceIndex datasource index
     * @param databaseName    database name
     * @param ddl             requested DDL
     * @param username        current login user for audit/context
     * @return precheck result and generated online DDL command
     */
    Map<String, Object> buildMysqlOnlineDdlPlan(int datasourceIndex, String databaseName, String ddl, String username);

    /**
     * Kill MySQL processes after re-validating each selected process still matches
     * the wsrep query filter.
     *
     * @param datasourceIndex datasource index
     * @param databaseName    database name
     * @param command        command filter used when querying (for kill validation)
     * @param eventType      event type filter used when querying (for kill validation)
     * @param processIds      MySQL process IDs
     * @param username        current login user for audit
     * @return operation result
     */
    Map<String, Object> killMysqlProcesses(int datasourceIndex, String databaseName, String command, String eventType, List<Long> processIds, String username);
}
