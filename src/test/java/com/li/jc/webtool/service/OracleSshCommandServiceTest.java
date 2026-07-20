package com.li.jc.webtool.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OracleSshCommandServiceTest {

    @Test
    void extractsHostFromEasyConnectUrl() {
        assertEquals("10.238.89.31", OracleSshCommandService.extractOracleHost(
                "jdbc:oracle:thin:@10.238.89.31:1521/orcl"));
    }

    @Test
    void extractsHostFromDescriptionUrl() {
        assertEquals("db01.example.com", OracleSshCommandService.extractOracleHost(
                "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=db01.example.com)(PORT=1521))"
                        + "(CONNECT_DATA=(SERVICE_NAME=orcl)))"));
    }

    @Test
    void buildsRacNodeUrlFromIpAndKeepsService() {
        assertEquals("jdbc:oracle:thin:@//10.238.89.35:1521/db19c",
                OracleHealthCheckService.buildNodeJdbcUrl(
                        "jdbc:oracle:thin:@//10.238.89.31:1521/db19c", "10.238.89.35"));
    }

    @Test
    void buildsRacNodeUrlWithExplicitPort() {
        assertEquals("jdbc:oracle:thin:@10.238.89.35:1522/db19c",
                OracleHealthCheckService.buildNodeJdbcUrl(
                        "jdbc:oracle:thin:@10.238.89.31:1521/db19c", "10.238.89.35:1522"));
    }

    @Test
    void buildsRacNodeDescriptionUrl() {
        assertEquals(
                "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=db02)(PORT=1522))"
                        + "(CONNECT_DATA=(SERVICE_NAME=orcl)))",
                OracleHealthCheckService.buildNodeJdbcUrl(
                        "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=db01)(PORT=1521))"
                                + "(CONNECT_DATA=(SERVICE_NAME=orcl)))", "db02:1522"));
    }

    @Test
    void buildsAndDeduplicatesAllConfiguredRacNodeUrls() {
        List<String> urls = OracleHealthCheckService.buildRacNodeJdbcUrls(
                "jdbc:oracle:thin:@10.238.89.31:1521/db19c",
                "10.238.89.31,10.238.89.32,10.238.89.33,10.238.89.34,10.238.89.35");
        assertEquals(5, urls.size());
        assertEquals("jdbc:oracle:thin:@10.238.89.35:1521/db19c", urls.get(4));
    }

    @Test
    void includesUrlNodeAndSingleSlaveNode() {
        List<String> urls = OracleHealthCheckService.buildRacNodeJdbcUrls(
                "jdbc:oracle:thin:@//10.238.89.31:1521/db19c", "10.238.89.32");
        assertEquals(2, urls.size());
        assertEquals("jdbc:oracle:thin:@//10.238.89.31:1521/db19c", urls.get(0));
        assertEquals("jdbc:oracle:thin:@//10.238.89.32:1521/db19c", urls.get(1));
    }
}
