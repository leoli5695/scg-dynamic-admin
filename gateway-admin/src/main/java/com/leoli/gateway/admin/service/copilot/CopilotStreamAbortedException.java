package com.leoli.gateway.admin.service.copilot;

/**
 * 信号异常：SSE 流式对话时客户端已断开，需要立即中断同步的工具调用循环，
 * 避免在无人接收的情况下继续消耗模型 token 与请求线程。
 * <p>
 * 仅在流式路径由进度回调抛出；非流式路径不会触发。
 *
 * @author leoli
 */
public class CopilotStreamAbortedException extends RuntimeException {

    public CopilotStreamAbortedException(String message) {
        super(message);
    }

    public CopilotStreamAbortedException(Throwable cause) {
        super(cause);
    }
}
