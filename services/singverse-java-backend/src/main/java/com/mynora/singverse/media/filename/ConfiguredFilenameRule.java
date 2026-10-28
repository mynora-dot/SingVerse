package com.mynora.singverse.media.filename;

import com.google.re2j.Pattern;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ConfiguredFilenameRule {
    static final Set<String> FIELDS = Set.of("artist", "title", "code", "language", "version", "quality", "extra");
    record Span(int start, int end) {}
    record Capture(Map<String, String> fields, List<String> residue) {
        Capture { fields = Map.copyOf(fields); residue = List.copyOf(residue); }
    }
    record Outcome(List<Capture> captures, boolean limited) {}
    record Token(String field, String literal) {}

    final FilenameRuleDefinition definition;
    private final Pattern regex;
    private final List<Token> tokens;
    private final String builtinId;

    ConfiguredFilenameRule(FilenameRuleDefinition definition, int index) {
        this.definition = definition;
        if (definition.name() == null || definition.name().isBlank() || definition.name().length() > 100)
            throw invalid(index, "name", "规则名称需要 1～100 个字符");
        if (definition.type() == null || definition.enabled() == null)
            throw invalid(index, "type", "规则类型和启用状态必填");
        String expression = definition.expression();
        var type = definition.type();
        builtinId = type == FilenameRuleDefinition.Type.BUILTIN ? definition.builtinId() : null;
        if (type == FilenameRuleDefinition.Type.BUILTIN) {
            var preset = BuiltinFilenameRules.find(builtinId);
            if (preset == null || expression != null)
                throw invalid(index, "builtinId", "内置规则引用不存在或试图改写内置表达式，请先复制为自定义规则");
            expression = preset.expression();
            type = preset.type();
        } else if (definition.builtinId() != null) throw invalid(index, "builtinId", "自定义规则不能引用内置 ID");
        if (expression == null || expression.isBlank() || expression.length() > 2048)
            throw invalid(index, "expression", "表达式需要 1～2048 个字符");
        try {
            if (type == FilenameRuleDefinition.Type.REGEX) {
                regex = Pattern.compile(expression);
                tokens = List.of();
                if (!FIELDS.containsAll(regex.namedGroups().keySet())
                        || !regex.namedGroups().keySet().containsAll(Set.of("artist", "title")))
                    throw invalid(index, "expression", "正则必须包含 artist/title 命名组，且只能使用支持的字段名");
            } else {
                regex = null;
                tokens = template(FilenameNormalizer.normalize(expression), index);
            }
        } catch (com.google.re2j.PatternSyntaxException error) {
            throw invalid(index, "expression", "正则语法不受支持：" + error.getDescription());
        }
    }

    private FilenameRuleValidationException invalid(int index, String field, String detail) {
        return new FilenameRuleValidationException(definition.id(), index, field, detail);
    }

    private List<Token> template(String expression, int index) {
        var result = new ArrayList<Token>();
        var names = new java.util.HashSet<String>();
        int offset = 0;
        while (offset < expression.length()) {
            int open = expression.indexOf('{', offset);
            if (open < 0) {
                String tail = expression.substring(offset);
                if (tail.contains("}")) throw invalid(index, "expression", "模板括号不匹配");
                result.add(new Token(null, tail));
                break;
            }
            if (open > offset) {
                String literal = expression.substring(offset, open);
                if (literal.contains("}")) throw invalid(index, "expression", "模板括号不匹配");
                result.add(new Token(null, literal));
            }
            int close = expression.indexOf('}', open);
            if (close < 0) throw invalid(index, "expression", "模板括号不匹配");
            String name = expression.substring(open + 1, close);
            if (!FIELDS.contains(name) || !names.add(name)) throw invalid(index, "expression", "字段不受支持或重复：" + name);
            if (!result.isEmpty() && result.getLast().field() != null)
                throw invalid(index, "expression", "相邻字段之间必须有字面分隔符");
            result.add(new Token(name, null));
            offset = close + 1;
        }
        if (!names.containsAll(Set.of("artist", "title"))) throw invalid(index, "expression", "模板必须包含 artist 和 title");
        return List.copyOf(result);
    }

    Outcome match(String input) {
        if (regex != null) {
            var matcher = regex.matcher(input);
            if (!matcher.matches()) return new Outcome(List.of(), false);
            var fields = new LinkedHashMap<String, String>();
            var spans = new ArrayList<Span>();
            for (String name : regex.namedGroups().keySet().stream().sorted().toList()) {
                if (matcher.group(name) != null) {
                    fields.put(name, matcher.group(name).strip());
                    spans.add(new Span(matcher.start(name), matcher.end(name)));
                }
            }
            return new Outcome(List.of(new Capture(fields, residue(input, spans))), false);
        }
        var search = new Search(input);
        search.visit(0, 0, new LinkedHashMap<>(), new ArrayList<>());
        return new Outcome(List.copyOf(search.results), search.limited);
    }

    boolean accepts(Map<String, String> fields) {
        if (fields.containsKey("code") && !fields.get("code").matches("[0-9]+")) return false;
        if (builtinId == null) return true;
        if (fields.containsKey("language") && TagDictionary.language(fields.get("language")) == null) return false;
        if (fields.containsKey("version") && TagDictionary.version(fields.get("version")) == null) return false;
        if (fields.containsKey("quality") && TagDictionary.quality(fields.get("quality")) == null) return false;
        if (builtinId.equals("coded-spaces") && (fields.getOrDefault("artist", "").matches(".*[-_].*")
                || fields.getOrDefault("title", "").matches(".*[-_].*"))) return false;
        if (builtinId.equals("hyphen") && fields.getOrDefault("artist", "").matches("[0-9]+ +.*")) return false;
        return !builtinId.equals("parentheses") || !(TagDictionary.known(fields.getOrDefault("artist", ""))
                || TagDictionary.hint(fields.getOrDefault("artist", "")) || fields.getOrDefault("artist", "").matches("[0-9]+"));
    }

    private static List<String> residue(String input, List<Span> spans) {
        var result = new ArrayList<String>();
        int end = 0;
        for (Span span : spans.stream().sorted(Comparator.comparingInt(Span::start)).toList()) {
            if (span.start() > end) {
                String gap = input.substring(end, span.start()).strip();
                if (FilenameNormalizer.meaningful(gap)) result.add(gap);
            }
            end = Math.max(end, span.end());
        }
        String tail = input.substring(end).strip();
        if (FilenameNormalizer.meaningful(tail)) result.add(tail);
        return result;
    }

    private final class Search {
        final String input;
        final List<Capture> results = new ArrayList<>();
        int steps;
        boolean limited;
        Search(String input) { this.input = input; }

        void visit(int tokenIndex, int offset, LinkedHashMap<String, String> fields, List<Span> spans) {
            if (results.size() >= 2 || limited) return;
            if (++steps > 20000) { limited = true; return; }
            if (tokenIndex == tokens.size()) {
                if (offset == input.length() && accepts(fields)) results.add(new Capture(fields, residue(input, spans)));
                return;
            }
            Token token = tokens.get(tokenIndex);
            if (token.field() == null) {
                if (input.startsWith(token.literal(), offset)) visit(tokenIndex + 1, offset + token.literal().length(), fields, spans);
                return;
            }
            if (tokenIndex == tokens.size() - 1) {
                capture(tokenIndex, offset, input.length(), fields, spans);
                return;
            }
            String boundary = tokens.get(tokenIndex + 1).literal();
            int next = input.indexOf(boundary, offset);
            while (next >= 0 && results.size() < 2 && !limited) {
                capture(tokenIndex, offset, next, fields, spans);
                next = input.indexOf(boundary, next + 1);
            }
        }

        void capture(int tokenIndex, int start, int end, LinkedHashMap<String, String> fields, List<Span> spans) {
            String name = tokens.get(tokenIndex).field();
            String value = input.substring(start, end).strip();
            if (name.equals("code") && !value.matches("[0-9]+")) return;
            fields.put(name, value);
            spans.add(new Span(start, end));
            visit(tokenIndex + 1, end, fields, spans);
            spans.removeLast();
            fields.remove(name);
        }
    }
}
