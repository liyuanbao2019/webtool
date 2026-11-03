package com.li.jc.webtool.service.impl;

import com.li.jc.webtool.config.DatabaseConfig;
import com.li.jc.webtool.config.SecurityConfig;
import com.li.jc.webtool.controller.DatabaseController;
import com.li.jc.webtool.dto.ExecuteSqlRequest;
import com.li.jc.webtool.dto.SqlResultResponse;
import com.li.jc.webtool.service.SqlAuditService;
import com.li.jc.webtool.service.SqlSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
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

    @ParameterizedTest
    @CsvSource({"2005, 0", "2005, 100", "2011, 0", "2011, 100"})
    void queriesPreserveCompleteLobTextWithAndWithoutPaging(int columnType, int pageSize) throws Exception {
        String text = longLobText();
        NClob lob = prepareLobResult(columnType, text);
        ExecuteSqlRequest request = request("SELECT IN_PARAM FROM interface_call_log_history_jiake");
        request.setPageSize(pageSize);

        SqlResultResponse response = service.executeSql(request);

        assertTrue(response.isSuccess(), response.getErrorMessage());
        assertEquals(1, response.getRowCount());
        assertEquals(text, response.getRows().get(0).get("IN_PARAM"));
        verify(lob).free();
    }

    @ParameterizedTest
    @ValueSource(ints = {Types.CLOB, Types.NCLOB})
    void csvExportPreservesCompleteLobTextAndEscapesQuotesAndNewlines(int columnType) throws Exception {
        String text = longLobText();
        prepareLobResult(columnType, text);
        ExecuteSqlRequest request = request("SELECT IN_PARAM FROM interface_call_log_history_jiake");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new DatabaseController(service).exportSql("csv", request, new MockHttpSession(), response);
        response.flushBuffer();

        assertEquals(200, response.getStatus());
        String newline = System.lineSeparator();
        assertEquals("\uFEFF\"IN_PARAM\"" + newline + "\"" + text.replace("\"", "\"\"") + "\"" + newline,
                response.getContentAsString());
        verify(statement).setMaxRows(0);
        verify(statement, never()).executeQuery(contains("PAGE_WRAP"));
    }

    @ParameterizedTest
    @ValueSource(ints = {Types.CLOB, Types.NCLOB})
    void lobValuesDistinguishNullFromEmptyText(int columnType) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        assertNull(DatabaseServiceImpl.getColumnValue(rs, 1, columnType));

        NClob lob = mock(NClob.class);
        when(rs.getObject(1)).thenReturn(lob);
        when(rs.getClob(1)).thenReturn(lob);
        when(rs.getNClob(1)).thenReturn(lob);
        when(lob.getCharacterStream()).thenReturn(new StringReader(""));
        assertEquals("", DatabaseServiceImpl.getColumnValue(rs, 1, columnType));
        verify(lob).free();
    }

    @Test
    void lobReadFailureFailsInsteadOfReturningPartialTextAndClosesResources() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        NClob lob = mock(NClob.class);
        Reader reader = mock(Reader.class);
        when(rs.getObject(1)).thenReturn(lob);
        when(rs.getClob(1)).thenReturn(lob);
        when(lob.getCharacterStream()).thenReturn(reader);
        when(reader.read(any(char[].class))).thenReturn(3).thenThrow(new IOException("read failed"));

        SQLException error = assertThrows(SQLException.class,
                () -> DatabaseServiceImpl.getColumnValue(rs, 1, Types.CLOB));

        assertInstanceOf(IOException.class, error.getCause());
        verify(reader).close();
        verify(lob).free();
    }

    private NClob prepareLobResult(int columnType, String text) throws Exception {
        when(connection.getMetaData().getDatabaseProductName()).thenReturn("Oracle");
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("IN_PARAM");
        when(metadata.getColumnType(1)).thenReturn(columnType);
        when(rs.findColumn("IN_PARAM")).thenReturn(1);
        when(rs.next()).thenReturn(true, false);
        NClob lob = mock(NClob.class);
        when(rs.getObject(1)).thenReturn(lob);
        when(rs.getClob(1)).thenReturn(lob);
        when(rs.getNClob(1)).thenReturn(lob);
        when(lob.getCharacterStream()).thenReturn(new StringReader(text));
        when(statement.executeQuery(anyString())).thenReturn(rs);
        ResultSet count = mock(ResultSet.class);
        when(count.next()).thenReturn(true);
        when(count.getLong(1)).thenReturn(1L);
        when(statement.executeQuery(contains("CNT_WRAP"))).thenReturn(count);
        return lob;
    }

    private String longLobText() {
        StringBuilder text = new StringBuilder("{\"items\":[\n");
        for (int i = 0; i < 3000; i++) {
            if (i > 0) text.append(',');
            text.append("\"中文物料😀-qty-10\"");
        }
        return text.append("\n],\"tail\":\"完整结束\"}").toString();
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
