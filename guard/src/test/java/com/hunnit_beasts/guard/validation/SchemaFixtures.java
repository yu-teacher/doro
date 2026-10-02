package com.hunnit_beasts.guard.validation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 운영과 같은 모양의 스키마: classpath schema.doro + doro-blog 의 blog-schema.doro 를 병합한 DSL. */
final class SchemaFixtures {

    static final String BLOG_SCHEMA = """
            type blog_series {
              relation author: user
              relation editor: author
              relation viewer: author | editor
            }

            type blog_post {
              relation author: user
              relation series: blog_series
              relation editor: author
              relation viewer: author | editor | series#viewer
              relation can_delete: author | post#author
            }

            type blog_comment {
              relation author: user
              relation post: blog_post
              relation can_delete: author | post#author
            }
            """;

    private SchemaFixtures() {
    }

    static String defaultSchema() {
        try (InputStream is = SchemaFixtures.class.getResourceAsStream("/schema.doro")) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String merged() {
        return defaultSchema() + "\n" + BLOG_SCHEMA;
    }
}
