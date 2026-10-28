package com.mynora.singverse.media.filename;

import static com.mynora.singverse.media.filename.FilenameParseResult.*;
import static com.mynora.singverse.media.filename.FilenameRuleDefinition.Type.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FilenameRuleEngineTest {
    final KtvFilenameParser parser = new KtvFilenameParser();

    static FilenameRuleDefinition template(String id, String expression) {
        return new FilenameRuleDefinition(id, id, TEMPLATE, true, expression, null);
    }
    static FilenameRuleDefinition regex(String expression) {
        return new FilenameRuleDefinition("regex", "高级正则", REGEX, true, expression, null);
    }

    @Test
    void reverseRuleAndFirstCompleteMatchAreExplicitAndAllCandidatesAreReported() {
        var reverse = template("reverse", "{title}-{artist}");
        var forward = template("forward", "{artist}-{title}");
        var preview = parser.preview("晴天-周杰伦.mkv", new FilenameRuleSet(List.of(reverse, forward)));
        assertThat(preview.result().artists()).containsExactly("周杰伦");
        assertThat(preview.result().title()).isEqualTo("晴天");
        assertThat(preview.result().parserRule()).isEqualTo(RuleId.CUSTOM_TEMPLATE);
        assertThat(preview.selectedRule().id()).isEqualTo("reverse");
        assertThat(preview.attempts()).extracting(FilenameRulePreview.Attempt::status)
                .containsExactly(FilenameRulePreview.Status.MATCHED, FilenameRulePreview.Status.MATCHED);
        assertThat(preview.attempts().get(1).captures()).containsEntry("artist", "晴天");
        assertThat(parser.parse("晴天-周杰伦.mkv", new FilenameRuleSet(List.of(forward, reverse))).artists()).containsExactly("晴天");
    }

    @Test
    void noHiddenFallbackAndDisabledRulesNeverMatch() {
        var off = new FilenameRuleDefinition("off", "停用", TEMPLATE, false, "{artist}-{title}", null);
        var result = parser.preview("周杰伦-晴天.mkv", new FilenameRuleSet(List.of(off)));
        assertThat(result.selectedRule()).isNull();
        assertThat(result.result().title()).isNull();
        assertThat(result.result().artists()).isEmpty();
        assertThat(result.result().parseStatus()).isEqualTo(ParseStatus.FAILED);
        assertThat(result.attempts().getFirst().status()).isEqualTo(FilenameRulePreview.Status.DISABLED);
        assertThat(parser.parse("周杰伦-晴天.mkv", new FilenameRuleSet(List.of())).parseStatus()).isEqualTo(ParseStatus.FAILED);
        assertThat(parser.parse("周杰伦-晴天.mkv").title()).isEqualTo("晴天");
    }

    @Test
    void templateAmbiguityIsNotResolvedByGreediness() {
        var result = parser.preview("A-B-C.mkv", new FilenameRuleSet(List.of(template("ambiguous", "{artist}-{title}"))));
        assertThat(result.selectedRule()).isNull();
        assertThat(result.result().parseStatus()).isEqualTo(ParseStatus.NEEDS_REVIEW);
        assertThat(result.attempts().getFirst().candidates()).containsExactlyInAnyOrder(
                java.util.Map.of("artist", "A", "title", "B-C"), java.util.Map.of("artist", "A-B", "title", "C"));
        assertThat(result.result().unparsedTokens()).containsExactly("A-B-C");
        var precise = parser.preview("AC-DC - Song-Part 2.mkv",
                new FilenameRuleSet(List.of(template("spaced", "{artist} - {title}"))));
        assertThat(precise.result().artists()).containsExactly("AC-DC");
        assertThat(precise.result().title()).isEqualTo("Song-Part 2");
    }

    @Test
    void regexMatchesWholeStemAndShowsPartialCaptures() {
        var rules = new FilenameRuleSet(List.of(regex("(?P<artist>[^-]*)-(?P<title>[^-]*)")));
        var complete = parser.preview("周杰伦 － 晴天.MKV", rules);
        assertThat(complete.matchTarget()).isEqualTo("周杰伦 - 晴天");
        assertThat(complete.result().title()).isEqualTo("晴天");
        assertThat(complete.result().artists()).containsExactly("周杰伦");
        assertThat(complete.result().parserRule()).isEqualTo(RuleId.CUSTOM_REGEX);
        var partial = parser.preview("-晴天.mkv", rules);
        assertThat(partial.selectedRule()).isNull();
        assertThat(partial.result().parseStatus()).isEqualTo(ParseStatus.PARTIAL);
        assertThat(partial.attempts().getFirst().captures()).containsEntry("title", "晴天");
        assertThat(parser.preview("A-B-C.mkv", rules).selectedRule()).isNull();
        var invalidCode = parser.preview("abc A-B.mkv", new FilenameRuleSet(List.of(
                regex("(?P<code>[^ ]+) (?P<artist>[^-]+)-(?P<title>.+)"))));
        assertThat(invalidCode.selectedRule()).isNull();
        assertThat(invalidCode.attempts().getFirst().status()).isEqualTo(FilenameRulePreview.Status.PARTIAL);
        assertThat(invalidCode.attempts().getFirst().captures()).containsEntry("code", "abc");
    }

    @Test
    void noPreStrippingAndMetadataEvidenceIsRetainedAfterCapture() {
        var rule = regex("(?P<code>[0-9]+) (?P<artist>[^-]+)-(?P<title>[^-]+)-(?P<language>[^-]+)-(?P<version>[^-]+)-(?P<quality>[^-]+)-(?P<extra>.+)");
        var preview = parser.preview("00128 周杰伦-晴天(副标题)-国语-伴奏-1080P-未知尾部.mkv", new FilenameRuleSet(List.of(rule)));
        var result = preview.result();
        assertThat(result.songCode()).isEqualTo("00128");
        assertThat(result.title()).isEqualTo("晴天(副标题)");
        assertThat(result.language()).isEqualTo(Language.MANDARIN);
        assertThat(result.versionType()).isEqualTo(VersionType.ACCOMPANIMENT);
        assertThat(result.qualityTags()).containsExactly("1080P");
        assertThat(result.unparsedTokens()).contains("未知尾部");
        assertThat(result.parseStatus()).isEqualTo(ParseStatus.SUCCESS);
        var literal = parser.parse("前缀:A-B:后缀.mkv",
                new FilenameRuleSet(List.of(template("literal", "前缀:{artist}-{title}:后缀"))));
        assertThat(literal.unparsedTokens()).containsExactly("前缀:", ":后缀");
    }

    @Test
    void builtinDefinitionsAreImmutableAndCanBeCopied() {
        var builtins = BuiltinFilenameRules.defaults();
        var off = builtins.stream().filter(d -> d.builtinId().equals("reverse-hyphen")).findFirst().orElseThrow();
        assertThat(off.enabled()).isFalse();
        assertThatThrownBy(() -> new FilenameRuleSet(List.of(new FilenameRuleDefinition("id", "篡改", BUILTIN, true, "{title}-{artist}", "hyphen"))))
                .isInstanceOf(FilenameRuleValidationException.class);
        var preset = BuiltinFilenameRules.catalog().stream().filter(p -> p.id().equals("reverse-hyphen")).findFirst().orElseThrow();
        assertThat(parser.parse("晴天-周杰伦.mkv", new FilenameRuleSet(List.of(template("copy", preset.expression())))).title()).isEqualTo("晴天");
        assertThatThrownBy(() -> BuiltinFilenameRules.catalog().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{artist}{title}", "{artist}-{artist}-{title}", "{foo}-{title}", "{artist}", "{artist}-{title", "{artist}-title}"})
    void invalidTemplatesIdentifyTheRuleAndField(String expression) {
        assertThatThrownBy(() -> new FilenameRuleSet(List.of(template("bad", expression))))
                .isInstanceOfSatisfying(FilenameRuleValidationException.class, error -> {
                    assertThat(error.ruleId()).isEqualTo("bad");
                    assertThat(error.ruleIndex()).isZero();
                    assertThat(error.field()).isEqualTo("expression");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"(?P<artist>a)(?P<title>b)(?=c)", "(?P<artist>a)(?P<title>b)\\1", "[", "(?P<artist>.+)", "(?P<artist>a)(?P<title>b)(?P<unsupported>c)"})
    void invalidOrUnsupportedRegexIsRejected(String expression) {
        assertThatThrownBy(() -> new FilenameRuleSet(List.of(regex(expression)))).isInstanceOf(FilenameRuleValidationException.class);
    }

    @Test
    void limitsAreAppliedBeforeMatchingAndDisabledRulesAreAlsoValidated() {
        assertThatThrownBy(() -> new FilenameRuleSet(null)).isInstanceOf(FilenameRuleValidationException.class);
        var many = IntStream.range(0, 101).mapToObj(i -> template("r" + i, "{artist}-{title}")).toList();
        assertThatThrownBy(() -> new FilenameRuleSet(many)).isInstanceOf(FilenameRuleValidationException.class);
        assertThatThrownBy(() -> new FilenameRuleSet(List.of(template("a", "x".repeat(2049))))).isInstanceOf(FilenameRuleValidationException.class);
        assertThatThrownBy(() -> new FilenameRuleSet(List.of(template("a", "{artist}-{title}"), template("a", "{title}-{artist}"))))
                .isInstanceOf(FilenameRuleValidationException.class);
        assertThatThrownBy(() -> new FilenameRuleSet(List.of(new FilenameRuleDefinition("off", "x", REGEX, false, "[", null))))
                .isInstanceOf(FilenameRuleValidationException.class);
        assertThatThrownBy(() -> parser.preview("x".repeat(1025), new FilenameRuleSet(List.of())))
                .isInstanceOf(FilenameRuleValidationException.class);
    }

    @Test
    void builtinLibraryRuleSeparatesSourceAnnotationsWithoutReadingMedia() {
        var rules = new FilenameRuleSet(BuiltinFilenameRules.defaults());
        var result = parser.preview("刀郎-孤独的牧羊人(消伴)-国语-流行.mkv", rules);
        assertThat(result.result().title()).isEqualTo("孤独的牧羊人");
        assertThat(result.result().artists()).containsExactly("刀郎");
        assertThat(result.result().unparsedTokens()).contains("消伴", "流行");
        assertThat(result.selectedRule().id()).isEqualTo("builtin-library");
        assertThat(result.result().versionType()).isEqualTo(VersionType.UNKNOWN);
        var coded = parser.parse("75 薛之谦-绅士[1080P]-国语-流行.mkv", rules);
        assertThat(coded.songCode()).isEqualTo("75");
        assertThat(coded.artists()).containsExactly("薛之谦");
        assertThat(coded.qualityTags()).containsExactly("1080P");
        var copied = parser.preview("歌手-歌名-国语-流行 (3).mkv", rules);
        assertThat(copied.result().artists()).containsExactly("歌手");
        assertThat(copied.result().title()).isEqualTo("歌名");
        assertThat(copied.selectedRule().id()).isEqualTo("builtin-library");
        assertThat(parser.preview("069 一场游戏一场梦 - 王杰.mkv", rules).selectedRule()).isNull();
    }

    @Test
    void definitionsAndPreviewCollectionsCannotBeMutatedAndEngineIsRepeatable() throws Exception {
        var source = new ArrayList<>(List.of(template("id", "{title}-{artist}")));
        var rules = new FilenameRuleSet(source);
        source.clear();
        var result = parser.preview("晴天-周杰伦.mkv", rules);
        assertThatThrownBy(() -> rules.definitions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.attempts().getFirst().captures().clear()).isInstanceOf(UnsupportedOperationException.class);
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Callable<FilenameRulePreview>> tasks = IntStream.range(0, 100)
                    .mapToObj(i -> (Callable<FilenameRulePreview>) () -> parser.preview("晴天-周杰伦.mkv", rules)).toList();
            for (var future : executor.invokeAll(tasks)) assertThat(future.get()).isEqualTo(result);
        }
    }

    @Test
    void hostileRegexAndHighlyAmbiguousTemplatesHaveBoundedExecution() {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            var rules = new FilenameRuleSet(List.of(regex("(?P<artist>(a+)+)-(?P<title>b)")));
            assertThat(parser.preview("a".repeat(1000) + "!.mkv", rules).selectedRule()).isNull();
            var template = new FilenameRuleSet(List.of(template("id", "{artist}-{title}-{extra}-END")));
            assertThat(parser.preview("A-".repeat(450) + "MISSING.mkv", template).selectedRule()).isNull();
        });
    }
}
