package com.leoli.gateway.admin.service;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;

/**
 * Handles kubeconfig parsing and ApiClient creation.
 */
@Slf4j
@Service
public class KubeConfigService {

    public ApiClient createApiClient(String kubeconfigContent) {
        try {
            StringReader reader = new StringReader(kubeconfigContent);
            KubeConfig kubeConfig = KubeConfig.loadKubeConfig(reader);
            ApiClient client = ClientBuilder.kubeconfig(kubeConfig).build();
            client.setVerifyingSsl(false);
            return client;
        } catch (IOException e) {
            log.error("Failed to create ApiClient: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public String extractServerUrl(String kubeconfigContent) {
        try {
            StringReader reader = new StringReader(kubeconfigContent);
            KubeConfig kubeConfig = KubeConfig.loadKubeConfig(reader);
            String currentContext = kubeConfig.getCurrentContext();
            if (currentContext == null) return null;

            List<?> contexts = kubeConfig.getContexts();
            if (contexts == null) return null;

            for (Object ctx : contexts) {
                if (!(ctx instanceof Map)) continue;
                Map<String, Object> context = (Map<String, Object>) ctx;
                Object nameObj = context.get("name");
                if (nameObj == null || !nameObj.equals(currentContext)) continue;

                Object contextDetailObj = context.get("context");
                if (!(contextDetailObj instanceof Map)) continue;
                Map<String, Object> contextDetail = (Map<String, Object>) contextDetailObj;
                Object clusterNameObj = contextDetail.get("cluster");
                if (!(clusterNameObj instanceof String)) continue;
                String clusterName = (String) clusterNameObj;

                List<?> clusters = kubeConfig.getClusters();
                if (clusters == null) continue;

                for (Object cls : clusters) {
                    if (!(cls instanceof Map)) continue;
                    Map<String, Object> cluster = (Map<String, Object>) cls;
                    Object clusterNameInList = cluster.get("name");
                    if (clusterNameInList == null || !clusterNameInList.equals(clusterName)) continue;

                    Object clusterDetailObj = cluster.get("cluster");
                    if (!(clusterDetailObj instanceof Map)) continue;
                    Map<String, Object> clusterDetail = (Map<String, Object>) clusterDetailObj;
                    Object serverObj = clusterDetail.get("server");
                    return serverObj != null ? serverObj.toString() : null;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract server URL: {}", e.getMessage());
        }
        return null;
    }

    public String extractCurrentContext(String kubeconfigContent) {
        try {
            StringReader reader = new StringReader(kubeconfigContent);
            KubeConfig kubeConfig = KubeConfig.loadKubeConfig(reader);
            return kubeConfig.getCurrentContext();
        } catch (Exception e) {
            log.warn("Failed to extract current context: {}", e.getMessage());
        }
        return null;
    }
}
