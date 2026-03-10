package com.example.gatewayadmin.center;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.kv.model.GetValue;
import com.ecwid.consul.v1.kv.model.PutParams;
import com.example.gatewayadmin.listener.ConfigChangeListener;
import com.example.gatewayadmin.properties.GatewayAdminProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Consul implementation of ConfigCenterService.
 * Auto-configured when gateway.center.type=consul.
 * Uses Consul KV store for configuration management.
 *
 * @author leoli
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "gateway.center.type", havingValue = "consul")
public class ConsulConfigCenterService implements ConfigCenterService {

    @Autowired
  private GatewayAdminProperties properties;

  private ConsulClient consulClient;
  private final ObjectMapper objectMapper;

    public ConsulConfigCenterService() {
        this.objectMapper=new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void init() {
      String host = properties.getConsul().getHost();
     int port = properties.getConsul().getPort();
      String prefix = properties.getConsul().getPrefix();
      
       this.consulClient = new ConsulClient(host, port);
        log.info("Consul Config Center initialized with host={}, port={}, prefix={}", 
             host, port, prefix);
    }

    @PreDestroy
    public void destroy() {
      if (consulClient != null) {
           try {
               // ConsulClient doesn't have a close method in older versions
                log.info("Consul Config Center shut down");
            } catch (Exception ex) {
                log.warn("Error shutting down Consul client: {}", ex.getMessage());
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getConfig(String dataId, Class<T> type) {
        try {
            String key = buildKey(dataId);
           GetValue value = consulClient.getKVValue(key).getValue();
           
          if (value == null) {
                log.debug("No configuration found for dataId: {} (key: {})", dataId, key);
              return null;
            }

            String content = Base64.getDecoder().decode(value.getValue()).toString();
            T config = objectMapper.readValue(content, type);
            log.debug("Loaded configuration from Consul: dataId={}, key={}, type={}", 
                   dataId, key, type.getSimpleName());
          return config;
        } catch (Exception ex) {
            log.error("Failed to get configuration from Consul: dataId={}, error={}", dataId, ex.getMessage(), ex);
            throw new RuntimeException("Failed to get config from Consul: " + dataId, ex);
        }
    }

    @Override
    public boolean publishConfig(String dataId, Object config) {
        try {
            String key = buildKey(dataId);
            String content = objectMapper.writeValueAsString(config);
            String encoded = Base64.getEncoder().encodeToString(content.getBytes());

           PutParams params = new PutParams();
           // Note: setAcquire may not be available in all Consul client versions
           // Using simple put without acquire for compatibility

           boolean result = consulClient.setKVValue(key, encoded).getValue();
          if (result) {
                log.info("Published configuration to Consul: key={}, contentLength={}", key, content.length());
            } else {
                log.warn("Failed to publish configuration to Consul: key={}", key);
            }
          return result;
        } catch (Exception ex) {
            log.error("Failed to publish configuration to Consul: dataId={}, error={}", dataId, ex.getMessage(), ex);
            throw new RuntimeException("Failed to publish config to Consul: " + dataId, ex);
        }
    }

    @Override
    public boolean removeConfig(String dataId) {
        try {
            String key = buildKey(dataId);
          consulClient.deleteKVValue(key);
            log.info("Removed configuration from Consul: key={}", key);
          return true;
        } catch(Exception ex) {
            log.error("Failed to remove configuration from Consul: dataId={}, error={}", dataId, ex.getMessage(), ex);
            throw new RuntimeException("Failed to remove config from Consul: " + dataId, ex);
        }
    }

    @Override
    public void addListener(String dataId, ConfigChangeListener listener) {
        // Note: Consul uses blocking queries (watches) for change notifications
        // This is a simplified implementation- in production, you'd use Consul's watch mechanism
        log.warn("Configuration listener not implemented for Consul.dataId={}. Use polling or external watch.", dataId);
    }

    @Override
    public void removeListener(String dataId) {
        // No-op since listener is not implemented
        log.debug("Remove listener called for dataId: {} (no-op)", dataId);
    }

    @Override
    public String getConfigCenterType() {
      return "consul";
    }

    /**
     * Build Consul KV key from dataId.
     * Example: dataId="gateway-plugins.json" -> key="config/gateway-plugins.json"
     */
  private String buildKey(String dataId) {
      return properties.getConsul().getPrefix() + "/" + dataId;
    }
}
