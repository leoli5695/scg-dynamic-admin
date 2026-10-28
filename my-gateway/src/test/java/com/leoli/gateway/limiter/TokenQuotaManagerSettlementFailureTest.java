package com.leoli.gateway.limiter;

import com.leoli.gateway.model.TokenQuotaConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TokenQuotaManagerSettlementFailureTest {
    @Test void refusedAdditionalConsumptionIsReportedAsFailedSettlement() {
        var manager = spy(new TokenQuotaManager()); var config = new TokenQuotaConfig();
        var stamp = new TokenQuotaManager.QuotaPeriodStamp("202610", "20261028");
        doReturn(false).when(manager).consumeTokens("t",20,config,stamp);
        assertThrows(IllegalStateException.class, () -> manager.settleTokenUsage("t",10,30,config,stamp));
    }
    @Test void failedRefundIsReportedAsFailedSettlement() {
        var manager = spy(new TokenQuotaManager()); var config = new TokenQuotaConfig();
        var stamp = new TokenQuotaManager.QuotaPeriodStamp("202610", "20261028");
        doReturn(false).when(manager).refundTokens("t",70,config,"ESTIMATE_DIFF",stamp);
        assertThrows(IllegalStateException.class, () -> manager.settleTokenUsage("t",100,30,config,stamp));
    }
}
