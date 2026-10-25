package com.leoli.gateway.admin.tool.handler;

import com.leoli.gateway.admin.model.*;
import com.leoli.gateway.admin.service.knowledge.KnowledgeSearchService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.leoli.gateway.admin.tool.ToolArgs.getIntArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getStringArg;

/**
 * Knowledge-base retrieval (RAG) tool.
 *
 * <p>Extracted from {@code ToolExecutor} as part of breaking up a 4241-line / 125-method / 27-dependency
 * god class. Moved mechanically: only the dispatch entry points changed signature
 * ({@code private Object executeX} became {@code public Object x}); helpers are verbatim. Behaviour is
 * unchanged, and {@code ToolExecutorTest} still exercises these tools end to end because it builds this
 * handler from the same mocks and passes it to the dispatcher.
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeToolHandler {

    private final com.leoli.gateway.admin.service.knowledge.KnowledgeSearchService knowledgeSearchService;

    public Object searchKnowledge(Map<String, Object> args) {
        String query = getStringArg(args, "query");
        int topK = getIntArg(args, "topK", 4);
        String sourceType = getStringArg(args, "sourceType");
        return knowledgeSearchService.search(query, topK, sourceType);
    }
}
