package com.leoli.gateway.admin.util;

/**
 * Escaping for values interpolated into PromQL label matchers.
 *
 * <p><b>Why this exists.</b> Monitoring queries were built by string concatenation in roughly 110
 * places across {@code PrometheusClient} and {@code PrometheusService}, and every one of them put a
 * caller-supplied value straight inside {@code {instance="..."}}. Nothing escaped a double quote, so
 * a value like {@code x"} or up{y=} closed the matcher and appended an arbitrary one: the query then
 * returned series from every target Prometheus scrapes - the whole monitored infrastructure, other
 * services' metrics - or, with a pathological regex matcher, burned enough CPU to degrade Prometheus
 * for everyone.
 *
 * <p>Reachable without elevated privilege: {@code MonitorController} takes these values as bare
 * {@code @RequestParam} on GET endpoints that only require authentication, and the AI copilot's
 * {@code query_*} tools pass {@code exporterUrl} and {@code topic} straight through from model
 * arguments. Those tools are flagged {@code readOnly=true}, so they skip the confirmation step that
 * write tools go through - a prompt injection is enough.
 *
 * <p>Escaping is applied at the small number of points where an untrusted value <em>becomes</em> a
 * label value (the {@code remapExporterUrl} assignments, the topic/queue filter builders,
 * {@code buildInstanceFilter} / {@code buildPodFilter}), not at each of the ~110 concatenations, so
 * there is one place to get right rather than a hundred.
 *
 * @author leoli
 */
public final class PromqlUtil {

    private PromqlUtil() {
    }

    /**
     * Escape a value so it is safe inside a double-quoted PromQL label matcher.
     *
     * <p>Backslash first, then quote and newline - escaping the quote first would let its new
     * backslash be escaped again. Null and empty pass through unchanged: an absent label value is
     * not an injection, and turning it into a literal would change query semantics.
     */
    public static String labelValue(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    /**
     * Build a complete {@code name="escapedValue"} matcher. Prefer this over hand-concatenating: it
     * is impossible to forget the escaping when the matcher and the escaping are the same call.
     */
    public static String labelMatcher(String name, String value) {
        return name + "=\"" + labelValue(value) + "\"";
    }
}
