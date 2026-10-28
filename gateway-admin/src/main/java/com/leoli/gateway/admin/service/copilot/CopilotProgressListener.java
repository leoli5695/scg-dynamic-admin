package com.leoli.gateway.admin.service.copilot;

import java.util.Map;

/**
 * AI Copilot 流式进度回调。
 * <p>
 * 工具调用循环是同步且可能耗时较久的（多轮模型调用 + 工具执行）。通过该回调把中间态
 * （正在思考、正在调用某工具、工具耗时/结果）实时推给调用方（SSE 端点），避免用户
 * 在整个过程里只看到一个转圈、毫无反馈。
 * <p>
 * 事件约定（type → data）：
 * <ul>
 *   <li>{@code status} → {stage, ...}：阶段提示，如 intent/thinking</li>
 *   <li>{@code tool}   → {toolName, status(running|success|error|confirm), durationMs?}</li>
 * </ul>
 * 最终回复不走此回调，由调用方在循环结束后作为终态事件下发。
 *
 * @author leoli
 */
@FunctionalInterface
public interface CopilotProgressListener {

    /**
     * 接收一个进度事件。实现方负责把它转成 SSE 帧下发；若下发失败（如客户端断开），
     * 应抛出运行时异常以中断整条循环，避免继续白白消耗模型 token。
     *
     * @param type 事件类型
     * @param data 事件数据（不含 null 值，便于直接 JSON 序列化）
     */
    void onEvent(String type, Map<String, Object> data);

    /** Check cancellation without emitting an event, including immediately before persistence. */
    default void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CopilotStreamAbortedException("Request interrupted");
    }

    /** 空实现：非流式路径使用。 */
    CopilotProgressListener NOOP = (type, data) -> { };
}
