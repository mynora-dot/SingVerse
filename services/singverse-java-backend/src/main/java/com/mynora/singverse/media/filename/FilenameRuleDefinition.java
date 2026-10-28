package com.mynora.singverse.media.filename;

/** Ordered, serializable configuration. Built-in references cannot redefine their expressions. */
public record FilenameRuleDefinition(String id, String name, Type type, Boolean enabled,
        String expression, String builtinId) {
    public enum Type { BUILTIN, TEMPLATE, REGEX }
}
