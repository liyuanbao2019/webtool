package com.gxcj.xjtool.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResultEditMetadata {
    private boolean editable;
    private String reason;
    private String tableName;
    private String schemaName;
    private String primaryKeyColumn;
    private List<String> editableColumns;
    private String editToken;
}
