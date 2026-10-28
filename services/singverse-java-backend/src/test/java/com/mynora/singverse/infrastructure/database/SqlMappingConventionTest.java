package com.mynora.singverse.infrastructure.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Keeps the repository-wide XML SQL convention in the regular Maven/CI checks. */
class SqlMappingConventionTest {
    private static final Pattern SQL_LITERAL = Pattern.compile(
            "(?i)\"\\s*(SELECT\\s+(?:[0-9*]|COUNT\\s*\\(|[^\"]*\\bFROM\\b)"
                    + "|(?:INSERT|REPLACE)\\s+INTO\\b|UPDATE\\s+\\w+\\s+SET\\b"
                    + "|DELETE\\s+FROM\\b|(?:CREATE|ALTER|DROP)\\s+TABLE\\b)");

    @Test
    void sqlDetectionDistinguishesSqlFromOrdinaryMessages() {
        for (String sql : new String[] {"select 1", "SELECT id FROM auth_account", "INSERT INTO auth_token",
                "UPDATE auth_account SET", "DELETE FROM auth_token", "CREATE TABLE example"}) {
            assertThat(SQL_LITERAL.matcher("\"" + sql + "\"").find()).as(sql).isTrue();
        }
        assertThat(SQL_LITERAL.matcher("\"Select only one database profile\"").find()).isFalse();
    }

    @Test
    void productionSqlLivesInXmlRatherThanJavaOrSqlAnnotations() throws Exception {
        var sqlAnnotation = Pattern.compile("@(Select|Insert|Update|Delete|SelectProvider|InsertProvider|UpdateProvider|DeleteProvider)\\b");
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                // Hikari validates its own connection before MyBatis can use it.
                if (file.getFileName().toString().equals("SQLiteDataSourceConfiguration.java")) {
                    source = source.replace("\"SELECT 1\"", "\"pool-probe\"");
                }
                assertThat(SQL_LITERAL.matcher(source).find()).as("Inline SQL in %s", file).isFalse();
                assertThat(sqlAnnotation.matcher(source).find()).as("SQL annotation in %s", file).isFalse();
                assertThat(source).as("Direct business JDBC in %s", file)
                        .doesNotContain("JdbcTemplate", "JdbcClient", ".prepareStatement(", ".createStatement(");
            }
        }
    }

    @Test
    void mapperXmlUsesBoundParameters() throws Exception {
        try (var files = Files.walk(Path.of("src/main/resources/mappers"))) {
            var xml = files.filter(p -> p.toString().endsWith(".xml")).toList();
            assertThat(xml).isNotEmpty();
            for (Path file : xml) {
                assertThat(Files.readString(file)).as("Unsafe substitution in %s", file)
                        .doesNotContain("${");
            }
        }
    }
}
