package com.example.gateway.refresher;

import com.example.gateway.strategy.StrategyType;
import com.example.gateway.strategy.StrategyManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Plugin configuration refresher.
 * Listens to gateway-plugins.json changes and refreshes strategies.
 */
@Slf4j
@Component
public class StrategyRefresher extends AbstractRefresher {
    
   private final StrategyManager strategyManager;
   private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Autowired
    public StrategyRefresher(StrategyManager strategyManager) {
        this.strategyManager = strategyManager;
    }
    
    @Override
   protected Object parseConfig(String json) {
        try {
         return objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("Failed to parse plugin config JSON: {}", e.getMessage());
          return null;
        }
    }
    
    @Override
   protected void updateCache(Object config) {
        // Cache is managed by individual strategies
        log.debug("Plugin config cache updated");
    }
    
    @Override
   protected void doRefresh(Object config) {
        if (config == null || !(config instanceof JsonNode)) {
            log.warn("Invalid plugin config, skipping refresh");
        return;
        }
        
        JsonNode root = (JsonNode) config;
        
        // Iterate through all plugin types
       for (StrategyType type : StrategyType.values()) {
            String configKey = type.getConfigKey();
            JsonNode pluginConfigs = root.get(configKey);
            
            if (pluginConfigs != null && pluginConfigs.isArray()) {
                // Merge all configs of this type into one map
               Map<String, Object> mergedConfig = mergePluginConfigs(pluginConfigs);
                
                // Refresh the strategy
               strategyManager.refreshStrategy(type, mergedConfig);
                log.info("Refreshed strategy {} with {} configs", 
                    type.getDisplayName(), mergedConfig.size());
            }
        }
    }
    
    /**
     * Merge multiple plugin configurations into one.
     */
    @SuppressWarnings("unchecked")
   private Map<String, Object> mergePluginConfigs(JsonNode configs) {
       Map<String, Object> result = new HashMap<>();
        
        for (JsonNode config : configs) {
            Iterator<Map.Entry<String, JsonNode>> fields = config.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
              result.put(entry.getKey(), toObject(entry.getValue()));
            }
        }
        
     return result;
    }
    
    /**
     * Convert JsonNode to Object.
     */
    private Object toObject(JsonNode node) {
        if (node.isTextual()) {
        return node.asText();
        } else if (node.isNumber()) {
        return node.asDouble();
        } else if (node.isBoolean()) {
        return node.asBoolean();
        } else if (node.isArray()) {
          java.util.List<Object> list = new java.util.ArrayList<>();
            for (JsonNode item : node) {
             list.add(toObject(item));
            }
        return list;
        } else if (node.isObject()) {
          Map<String, Object> map = new HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
              map.put(entry.getKey(), toObject(entry.getValue()));
            }
        return map;
        }
     return node.asText();
    }
}
