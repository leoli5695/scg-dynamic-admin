package com.example.gateway.strategy.ipfilter;

import com.example.gateway.strategy.AbstractStrategy;
import com.example.gateway.strategy.StrategyType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP filter strategy implementation.
 * Supports whitelist and blacklist modes.
 */
@Slf4j
@Component
public class IPFilterStrategy extends AbstractStrategy {
    
    private final Set<String> whitelist = ConcurrentHashMap.newKeySet();
    private final Set<String> blacklist = ConcurrentHashMap.newKeySet();
    private String mode = "blacklist"; // or "whitelist"
    
    @Override
    public StrategyType getType() {
    return StrategyType.IP_FILTER;
    }
    
    @Override
    public void apply(Map<String, Object> context) {
        if (!isEnabled()) {
            log.trace("IP filter strategy disabled, skipping");
       return;
        }
        
        String clientIp = (String) context.get("clientIp");
        if (clientIp == null) {
            log.warn("No clientIp in context, cannot apply IP filter");
       return;
        }
        
        boolean allowed = checkIp(clientIp);
        context.put("ipFilterAllowed", allowed);
        
        if (!allowed) {
            log.warn("IP {} blocked by filter", clientIp);
        }
        
        log.debug("IP filter check: ip={}, allowed={}", clientIp, allowed);
    }
    
    @Override
    public void refresh(Object config) {
        super.refresh(config);
        if (config instanceof Map) {
            Object modeObj = ((Map<?, ?>) config).get("mode");
            Object whitelistObj = ((Map<?, ?>) config).get("whitelist");
            Object blacklistObj = ((Map<?, ?>) config).get("blacklist");
            
            if (modeObj != null) {
                this.mode = modeObj.toString().toLowerCase();
            }
            
          refreshWhitelist(whitelistObj);
          refreshBlacklist(blacklistObj);
            
            log.info("IP filter config updated: mode={}, whitelistSize={}, blacklistSize={}",
                    mode, whitelist.size(), blacklist.size());
        }
    }
    
    /**
     * Check if IP is allowed.
     */
    private boolean checkIp(String clientIp) {
        if ("whitelist".equals(mode)) {
            // Whitelist mode: only allow IPs in whitelist
          return whitelist.isEmpty() || whitelist.contains(clientIp);
        } else {
            // Blacklist mode: block IPs in blacklist
          return !blacklist.contains(clientIp);
        }
    }
    
    /**
     * Refresh whitelist from configuration.
     */
    @SuppressWarnings("unchecked")
   private void refreshWhitelist(Object whitelistObj) {
       whitelist.clear();
        if (whitelistObj instanceof List) {
            for (Object ip : (List<?>) whitelistObj) {
               whitelist.add(ip.toString().trim());
            }
        }
    }
    
    /**
     * Refresh blacklist from configuration.
     */
    @SuppressWarnings("unchecked")
   private void refreshBlacklist(Object blacklistObj) {
       blacklist.clear();
        if (blacklistObj instanceof List) {
            for (Object ip : (List<?>) blacklistObj) {
               blacklist.add(ip.toString().trim());
            }
        }
    }
}
