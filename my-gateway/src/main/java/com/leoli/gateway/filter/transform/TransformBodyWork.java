package com.leoli.gateway.filter.transform;

import com.leoli.gateway.util.BodyWorkPool;
import com.leoli.gateway.util.ByteBudget;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Request and response transforms share admission and CPU capacity in the application context. */
@Component
public class TransformBodyWork {
    final ByteBudget budget = new ByteBudget();
    final BodyWorkPool pool = new BodyWorkPool("gateway-body-transform", 2, 8);
    @Value("${gateway.transform.max-inflight-body-bytes:134217728}")
    long maxBytes = 128L * 1024 * 1024;
    @jakarta.annotation.PreDestroy
    public void close() { pool.close(); }
}
