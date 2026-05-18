package com.gxcj.xjtool.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;

/**
 * 脚本安全扫描结果
 * 封装脚本内容扫描后的检测结果，包含危险等级、危险操作列表和建议
 */
public class ScriptScanResult {

    /** 扫描是否通过（无危险操作或用户已确认） */
    private boolean passed;

    /** 危险等级: none(安全) / low(低危) / medium(中危) / high(高危) */
    private String riskLevel;

    /** 是否需要用户确认 */
    private boolean needConfirm;

    /** 检测到的危险操作列表 */
    private List<DangerousOperation> dangerousOperations;

    /** 扫描备注信息 */
    private String message;

    /** 扫描的脚本内容（脱敏版，仅在需要时返回） */
    private String sanitizedContent;

    public ScriptScanResult() {
        this.passed = true;
        this.riskLevel = "none";
        this.needConfirm = false;
        this.dangerousOperations = new ArrayList<>();
        this.message = "脚本安全";
    }

    /**
     * 创建一个安全的扫描结果
     */
    public static ScriptScanResult safe() {
        ScriptScanResult result = new ScriptScanResult();
        result.passed = true;
        result.riskLevel = "none";
        result.needConfirm = false;
        result.message = "脚本安全，未检测到危险操作";
        return result;
    }

    /**
     * 创建一个需要确认的扫描结果
     */
    public static ScriptScanResult needConfirm(List<DangerousOperation> operations, String riskLevel, String message) {
        ScriptScanResult result = new ScriptScanResult();
        result.passed = false;
        result.riskLevel = riskLevel;
        result.needConfirm = true;
        result.dangerousOperations = operations;
        result.message = message;
        return result;
    }

    /**
     * 创建一个阻断的扫描结果（不允许执行）
     */
    public static ScriptScanResult blocked(String reason) {
        ScriptScanResult result = new ScriptScanResult();
        result.passed = false;
        result.riskLevel = "high";
        result.needConfirm = false;
        result.message = reason;
        return result;
    }

    // Getters and Setters

    public boolean isPassed() {
        return passed;
    }

    public void setPassed(boolean passed) {
        this.passed = passed;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public boolean isNeedConfirm() {
        return needConfirm;
    }

    public void setNeedConfirm(boolean needConfirm) {
        this.needConfirm = needConfirm;
    }

    public List<DangerousOperation> getDangerousOperations() {
        return dangerousOperations;
    }

    public void setDangerousOperations(List<DangerousOperation> dangerousOperations) {
        this.dangerousOperations = dangerousOperations;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSanitizedContent() {
        return sanitizedContent;
    }

    public void setSanitizedContent(String sanitizedContent) {
        this.sanitizedContent = sanitizedContent;
    }

    /**
     * 危险操作描述
     */
    public static class DangerousOperation {

        /** 危险操作类型 */
        private String type;

        /** 危险操作子类型 */
        private String subType;

        /** 危险操作的原始文本（脱敏） */
        private String originalText;

        /** 危险操作描述 */
        private String description;

        /** 危险等级 */
        private String riskLevel;

        /** 建议操作 */
        private String suggestion;

        /** 内部去重键（同脚本多次相同规则匹配时区分），不参与展示 */
        @JsonIgnore
        private String dedupeKey;

        /** 描述 i18n key（格式: terminal.desc_xxx） */
        private String descriptionKey;

        /** 建议 i18n key（格式: terminal.sugg_xxx） */
        private String suggestionKey;

        public DangerousOperation() {}

        public DangerousOperation(String type, String subType, String originalText,
                                  String description, String riskLevel, String suggestion) {
            this.type = type;
            this.subType = subType;
            this.originalText = originalText;
            this.description = description;
            this.riskLevel = riskLevel;
            this.suggestion = suggestion;
        }

        // Getters and Setters

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getSubType() {
            return subType;
        }

        public void setSubType(String subType) {
            this.subType = subType;
        }

        public String getOriginalText() {
            return originalText;
        }

        public void setOriginalText(String originalText) {
            this.originalText = originalText;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getRiskLevel() {
            return riskLevel;
        }

        public void setRiskLevel(String riskLevel) {
            this.riskLevel = riskLevel;
        }

        public String getSuggestion() {
            return suggestion;
        }

        public void setSuggestion(String suggestion) {
            this.suggestion = suggestion;
        }

        public String getDedupeKey() {
            return dedupeKey;
        }

        public void setDedupeKey(String dedupeKey) {
            this.dedupeKey = dedupeKey;
        }

        public String getDescriptionKey() {
            return descriptionKey;
        }

        public void setDescriptionKey(String descriptionKey) {
            this.descriptionKey = descriptionKey;
        }

        public String getSuggestionKey() {
            return suggestionKey;
        }

        public void setSuggestionKey(String suggestionKey) {
            this.suggestionKey = suggestionKey;
        }
    }
}
