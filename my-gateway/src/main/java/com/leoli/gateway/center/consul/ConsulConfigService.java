package com.leoli.gateway.center.consul;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.kv.model.GetValue;
import com.leoli.gateway.center.spi.AbstractConfigService;
import com.leoli.gateway.enums.CenterType;
import lombok.extern.slf4j.Slf4j;

import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consul implementation of ConfigCenterService.
 */
@Slf4j
public class ConsulConfigService extends AbstractConfigService {

    private final String prefix;
    private final ConsulClient consulClient;

    public ConsulConfigService(ConsulClient consulClient, String prefix) {
        this.prefix = prefix;
        this.consulClient = consulClient;
        log.info("ConsulConfigService initialized with prefix: {}", prefix);
    }

    @Override
    public String getConfig(String dataId, String group) {
        try {
            // Consul uses key-value format: prefix/group/dataId
            String key = buildKey(dataId, group);
            Response<GetValue> response = consulClient.getKVValue(key);
            if (Objects.nonNull(response.getValue())) {
                String encodedValue = response.getValue().getValue();
                return new String(Base64.getDecoder().decode(encodedValue));
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to get config from Consul: {}#{}", dataId, group, e);
            return null;
        }
    }

    @Override
    public Map<String, Object> getAllConfigData(String dataId, String group) {
        String content = getConfig(dataId, group);
        if (content == null || content.trim().isEmpty()) {
            return new ConcurrentHashMap<>();
        }

        // Parse JSON content to Map
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(content, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse config data as JSON: {}", dataId, e);
            return new ConcurrentHashMap<>();
        }
    }

    @Override
    public void addListener(String dataId, String group, ConfigListener listener) {
        super.addListener(dataId, group, listener);

        // Consul uses watch mechanism for configuration changes
        // In production, you would use Consul's watch API to monitor key changes
        log.debug("Consul listener added for: {}#{}", dataId, group);
    }

    @Override
    public void removeListener(String dataId, String group, ConfigListener listener) {
        super.removeListener(dataId, group, listener);
        log.debug("Listener removed for: {}#{}", dataId, group);
    }

    @Override
    public CenterType getCenterType() {
        return CenterType.CONSUL;
    }

    /**
     * Build Consul key from dataId and group.
     */
    protected String buildKey(String dataId, String group) {
        return prefix + "/" + group + "/" + dataId;
    }
}
