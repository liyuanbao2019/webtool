package com.li.jc.webtool.service.impl;

import com.li.jc.webtool.config.DatabaseConfig;
import com.li.jc.webtool.config.SecurityConfig;
import com.li.jc.webtool.dto.ExecuteSqlRequest;
import com.li.jc.webtool.dto.SqlResultResponse;
import com.li.jc.webtool.service.SqlAuditService;
import com.li.jc.webtool.service.SqlSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatabaseResultSetExecutionTest {
    private DatabaseServiceImpl service;
    private Connection connection;
    private Statement statement;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseConnectionManager connections = mock(DatabaseConnectionManager.class);
        DatabaseDataSourceRegistry sources = mock(DatabaseDataSourceRegistry.class);
        SqlSecurityService security = mock(SqlSecurityService.class);
        DatabaseConfig.DataSourceConfig source = new DatabaseConfig.DataSourceConfig();
        source.setType("MYSQL");
        when(sources.get(0)).thenReturn(source);
        when(security.checkSql(anyString(), any())).thenReturn(SqlSecurityService.SqlCheckResult.safe());
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        DatabaseMetaData databaseMetadata = mock(DatabaseMetaData.class);
        when(connections.getConnection(0)).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(databaseMetadata);
        when(databaseMetadata.getDatabaseProductName()).thenReturn("MySQL");
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            ResultSet rs = mock(ResultSet.class);
            ResultSetMetaData metadata = mock(ResultSetMetaData.class);
            when(rs.getMetaData()).thenReturn(metadata);
            when(metadata.getColumnCount()).thenReturn(2);
            when(metadata.getColumnLabel(1)).thenReturn("Variable_name");
            when(metadata.getColumnLabel(2)).thenReturn("Value");
            when(metadata.getColumnType(anyInt())).thenReturn(Types.VARCHAR);
            when(rs.next()).thenReturn(true, false);
            when(rs.getObject(1)).thenReturn("tmpdir");
            when(rs.getObject(2)).thenReturn("/tmp");
            return rs;
        });
        service = new DatabaseServiceImpl(new DatabaseConfig(), mock(SqlAuditService.class), security,
                null, new SecurityConfig(), connections, sources, null, new DatabaseSqlParser());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHOW VARIABLES LIKE 'tmpdir';", "SHOW GLOBAL STATUS;",
            "DESC demo;", "DESCRIBE demo;", "EXPLAIN SELECT * FROM demo;"})
    void diagnosticStatementsReturnRowsWithoutSelectPagination(String sql) throws Exception {
        SqlResultResponse response = service.executeSql(request(sql));

        assertTrue(response.isSuccess(), response.getErrorMessage());
        assertEquals(1, response.getRowCount());
        assertEquals("/tmp", response.getRows().get(0).get("Value"));
        verify(statement, times(1)).executeQuery(anyString());
        verify(statement, never()).executeQuery(contains("CNT_WRAP"));
        verify(statement, never()).executeQuery(contains("PAGE_WRAP"));
        verify(statement, never()).executeUpdate(anyString());
    }

    @Test
    void batchMixesDiagnosticResultsWithUpdateCounts() throws Exception {
        when(statement.executeUpdate(anyString())).thenReturn(3);
        SqlResultResponse response = service.executeSql(request(
                "SHOW VARIABLES LIKE 'tmpdir'; UPDATE demo SET value = 1 WHERE id = 1;"));

        assertTrue(response.isSuccess(), response.getErrorMessage());
        assertTrue(response.isBatch());
        assertEquals(2, response.getMultiResults().size());
        assertEquals(1, response.getMultiResults().get(0).getRowCount());
        assertEquals(3, response.getAffectedRows());
        verify(statement).executeQuery(startsWith("SHOW"));
        verify(statement).executeUpdate(startsWith("UPDATE"));
        verify(connection).commit();
    }

    private ExecuteSqlRequest request(String sql) {
        ExecuteSqlRequest request = new ExecuteSqlRequest();
        request.setDatasourceIndex(0);
        request.setSql(sql);
        request.setPage(1);
        request.setPageSize(100);
        return request;
    }
}
