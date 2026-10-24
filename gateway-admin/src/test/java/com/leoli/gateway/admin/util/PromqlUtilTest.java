package com.leoli.gateway.admin.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PromQL label-value escaping.
 *
 * <p>The injection this prevents: monitoring queries were built by concatenation in ~110 places and
 * none escaped a double quote, so {@code MonitorController}'s bare {@code @RequestParam}s - and the
 * AI copilot's {@code query_*} tools, which are {@code readOnly=true} and therefore skip the
 * confirmation step - could close the matcher and append a selector of their own, reading series
 * from every target Prometheus scrapes or burning CPU with a pathological regex.
 */
class PromqlUtilTest {

    @Test
    @DisplayName("ordinary label values pass through untouched, so real queries are unaffected")
    void ordinaryValuesAreUnchanged() {
        // This is the assertion that matters most: instance labels are host:port, and if escaping
        // mangled them every monitoring query in the product would silently return nothing.
        assertEquals("redis-exporter:9121", PromqlUtil.labelValue("redis-exporter:9121"));
        assertEquals("10.244.1.7:8080", PromqlUtil.labelValue("10.244.1.7:8080"));
        assertEquals("gateway-1", PromqlUtil.labelValue("gateway-1"));
        assertEquals("end of minor GC", PromqlUtil.labelValue("end of minor GC"));
        assertEquals("TopicTest-_%. ", PromqlUtil.labelValue("TopicTest-_%. "));
    }

    @Test
    @DisplayName("the matcher-breaking payload is neutralised")
    void injectionPayloadIsNeutralised() {
        String payload = "x\"} or up{y=\"";

        String escaped = PromqlUtil.labelValue(payload);

        // Both quotes are escaped, so neither can terminate the string literal in the selector and
        // the trailing `or up{y=` stays inert text inside one label value.
        assertEquals("x\\\"} or up{y=\\\"", escaped);
        assertEquals("up{instance=\"x\\\"} or up{y=\\\"\"}",
                "up{instance=\"" + escaped + "\"}");
    }

    @Test
    @DisplayName("backslash is escaped first, so an escaped quote cannot be smuggled through")
    void backslashIsEscapedFirst() {
        // Input is a literal backslash followed by a quote. Escaping the quote first would produce
        // \" and then the backslash pass would turn it into \\" - which re-opens the string.
        assertEquals("\\\\\\\"", PromqlUtil.labelValue("\\\""));
        assertEquals("a\\\\b", PromqlUtil.labelValue("a\\b"));
    }

    @Test
    @DisplayName("control characters that would break the query string are escaped")
    void controlCharactersAreEscaped() {
        assertEquals("a\\nb", PromqlUtil.labelValue("a\nb"));
        assertEquals("a\\rb", PromqlUtil.labelValue("a\rb"));
        assertEquals("a\\r\\nb", PromqlUtil.labelValue("a\r\nb"));
    }

    @Test
    @DisplayName("null and empty pass through - an absent label value is not an injection")
    void nullAndEmptyPassThrough() {
        assertNull(PromqlUtil.labelValue(null));
        assertEquals("", PromqlUtil.labelValue(""));
    }

    @Test
    @DisplayName("labelMatcher builds a complete, escaped matcher")
    void labelMatcherBuildsSelector() {
        assertEquals("instance=\"redis-exporter:9121\"",
                PromqlUtil.labelMatcher("instance", "redis-exporter:9121"));
        assertEquals("gateway_instance_id=\"a\\\"b\"",
                PromqlUtil.labelMatcher("gateway_instance_id", "a\"b"));
    }

    @Test
    @DisplayName("every quote in the output is escaped, whatever the input mixes in")
    void repeatedSpecialCharacters() {
        String nasty = "\\\\\"\"" + "} or vector(1) or {__name__=~\".+";
        String escaped = PromqlUtil.labelValue(nasty);

        // The invariant that actually matters: no quote in the output can terminate the selector's
        // string literal. Note that a substring check for `"} or` would be meaningless here - the
        // escaped output still contains those characters, just preceded by a backslash that
        // neutralises them.
        for (int i = 0; i < escaped.length(); i++) {
            if (escaped.charAt(i) == '"') {
                assertTrue(i > 0 && escaped.charAt(i - 1) == '\\',
                        "unescaped quote at index " + i + " in: " + escaped);
            }
        }
        // And every original backslash survived as an escaped pair, so the escaping cannot be
        // undone by a second pass reading `\\"` as an escaped quote.
        assertTrue(escaped.startsWith("\\\\\\\\"), "leading backslashes must each be doubled: " + escaped);
    }
}
