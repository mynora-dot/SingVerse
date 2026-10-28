package com.mynora.singverse.media.filename;

public final class FilenameRuleValidationException extends IllegalArgumentException {
    private final String ruleId;
    private final int ruleIndex;
    private final String field;

    public FilenameRuleValidationException(String ruleId, int ruleIndex, String field, String detail) {
        super(detail);
        this.ruleId = ruleId;
        this.ruleIndex = ruleIndex;
        this.field = field;
    }

    public String ruleId() { return ruleId; }
    public int ruleIndex() { return ruleIndex; }
    public String field() { return field; }
}
