package com.gxcj.xjtool.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseControllerCompatibilityTest {

    @Test
    void exposesNewDatabaseRouteAndLegacyOracleRoute() {
        RequestMapping mapping = DatabaseController.class.getAnnotation(RequestMapping.class);
        assertTrue(Arrays.asList(mapping.value()).contains("/api/database"));
        assertTrue(Arrays.asList(mapping.value()).contains("/api/oracle"));
    }
}
