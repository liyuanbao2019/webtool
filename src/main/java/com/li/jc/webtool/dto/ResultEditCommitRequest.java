package com.li.jc.webtool.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ResultEditCommitRequest {
    private Integer datasourceIndex;
    private String tableName;
    private String schemaName;
    private String primaryKeyColumn;
    private String editToken;
    private List<CellEdit> edits;
    private String username;

    @Data
    public static class CellEdit {
        private String columnName;
        private Object newValue;
        private Object originalValue;
        private Map<String, Object> primaryKeyValues;
    }
}
