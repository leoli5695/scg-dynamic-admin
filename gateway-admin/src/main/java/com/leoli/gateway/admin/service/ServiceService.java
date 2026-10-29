package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.RouteEntity;
import com.leoli.gateway.admin.model.ServiceEntity;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.ServiceRepository;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.ServiceDefinition;
import com.leoli.gateway.admin.repository.RouteRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service configuration management service with per-service incremental format.
 * Each service is stored in Nacos as an independent key: config.gateway.services.service-{serviceName}
 *
 * @author leoli
 */
@Slf4j
@Service
public class ServiceService {

  private static final String SERVICE_PREFIX = "config.gateway.service-";
  private static final String SERVICES_INDEX = "config.gateway.metadata.services-index";
  private static final String GROUP = "DEFAULT_GROUP";

  @Autowired
  private ConfigCenterService configCenterService;

  @Autowired
  private ServiceRepository serviceRepository;

  @Autowired
  private RouteRepository routeRepository;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private GatewayInstanceRepository gatewayInstanceRepository;

  @Autowired
  private ProviderCredentialService providerCredentialService;

  // Local cache: serviceName -> ServiceDefinition
  private final ConcurrentHashMap<String, ServiceDefinition> serviceCache = new ConcurrentHashMap<>();

  @PostConstruct
  public void init() {
    // Load services from database to cache
    loadServicesFromDatabase();
    // Sync all services to Nacos (fix data inconsistency)
    syncServicesToNacosOnStartup();
    // Rebuild services index for each instance's namespace (not public)
    rebuildServicesIndexForAllInstances();
    log.info("ServiceService initialized with per-service incremental format");
  }

  /**
   * Get all services from cache with serviceId for UI display.
   */
  public List<ServiceDefinition> getAllServices() {
    List<ServiceDefinition> result = new ArrayList<>();
    
    // Load from database to get serviceId
    List<ServiceEntity> entities = serviceRepository.findAll();
    for (ServiceEntity entity : entities) {
      ServiceDefinition definition = serviceCache.get(entity.getServiceName());
      if (definition != null) {
        // Set serviceId for display purposes
        definition.setServiceId(entity.getServiceId());
        result.add(definition);
      }
    }
    
    return result;
  }

  /**
   * Get service by name.
   */
  public ServiceDefinition getServiceByName(String name) {
    if (name == null || name.isEmpty()) {
      return null;
    }
    return serviceCache.get(name);
  }

  /**
   * Get Nacos namespace from instance ID.
   * Returns null for default namespace if instance not found.
   */
  private String getNacosNamespace(String instanceId) {
    if (instanceId == null || instanceId.isEmpty()) {
      return null; // Use default namespace
    }
    Optional<GatewayInstanceEntity> instance = gatewayInstanceRepository.findByInstanceId(instanceId);
    return instance.map(GatewayInstanceEntity::getNacosNamespace).orElse(null);
  }

  /**
   * Get all services for a specific instance.
   */
  public List<ServiceDefinition> getAllServicesByInstanceId(String instanceId) {
    List<ServiceEntity> entities = serviceRepository.findByInstanceId(instanceId);
    List<ServiceDefinition> result = new ArrayList<>();
    for (ServiceEntity entity : entities) {
      ServiceDefinition definition = toDefinition(entity);
      if (definition != null) {
        definition.setServiceId(entity.getServiceId());
        result.add(definition);
      }
    }
    return result;
  }

  /**
   * Create service with dual-write to database and Nacos (per-service format).
   * Description is saved to database only, not pushed to Nacos metadata.
   */
  @Transactional(rollbackFor = Exception.class)
  public ServiceEntity createService(ServiceDefinition service) {
    return createService(service, null);
  }

  /**
   * Create service for a specific instance with dual-write to database and Nacos.
   * @param service Service definition
   * @param instanceId Gateway instance ID (optional, uses default namespace if null)
   */
  @Transactional(rollbackFor = Exception.class)
  public ServiceEntity createService(ServiceDefinition service, String instanceId) {
    if (service == null || service.getName() == null || service.getName().isEmpty()) {
      throw new IllegalArgumentException("Invalid service definition");
    }

    String serviceName = service.getName();

    // Check existence based on instance scope
    if (instanceId != null && !instanceId.isEmpty()) {
      if (serviceRepository.existsByServiceNameAndInstanceId(serviceName, instanceId)) {
        throw new IllegalArgumentException("Service already exists in this instance: " + serviceName);
      }
    } else {
      if (serviceCache.containsKey(serviceName)) {
        throw new IllegalArgumentException("Service already exists: " + serviceName);
      }
    }

    // Generate UUID first (needed for both DB and Nacos)
    String generatedServiceId = java.util.UUID.randomUUID().toString();

    // Set serviceId in ServiceDefinition BEFORE converting to entity
    service.setServiceId(generatedServiceId);

    // AI services: encrypt the supplied API key into provider_credentials and keep only the ref.
    // Runs inside this @Transactional method, so a later failure rolls the credential back too.
    applyAiCredential(service);

    // Get Nacos namespace from instance
    String nacosNamespace = getNacosNamespace(instanceId);

    // STRICT CHECK: Require valid instanceId for all services
    // Services MUST be associated with a gateway instance with valid nacosNamespace
    // Do NOT allow publishing to public namespace (null namespace)
    if (nacosNamespace == null || nacosNamespace.isEmpty()) {
        throw new IllegalArgumentException(
            "Service MUST be associated with a valid gateway instance. " +
            "instanceId=" + instanceId + " does not have a valid nacosNamespace. " +
            "Create a gateway instance first or provide a valid instanceId.");
    }

    // 1. Convert to entity and save to database
    log.info("Saving service to database: {}, instanceId={}, namespace={}", serviceName, instanceId, nacosNamespace);
    ServiceEntity entity = toEntity(service);
    entity.setServiceName(serviceName);
    entity.setServiceId(generatedServiceId);
    entity.setInstanceId(instanceId);
    entity = serviceRepository.save(entity);

    // 3. Push to Nacos (per-service format using service_id UUID)
    String serviceDataId = SERVICE_PREFIX + entity.getServiceId();

    // Create a copy without description for Nacos
    ServiceDefinition nacosConfig = objectMapper.convertValue(service, ServiceDefinition.class);
    nacosConfig.setDescription(null);
    nacosConfig.setApiKey(null);
    nacosConfig.setName(service.getName());
    nacosConfig.setServiceId(entity.getServiceId());
    nacosConfig.setLoadBalancer(service.getLoadBalancer());
    nacosConfig.setInstances(service.getInstances());

    publishServiceOrThrow(serviceDataId, nacosNamespace, nacosConfig);
    log.info("Service pushed to Nacos: {} (namespace: {})", serviceDataId, nacosNamespace);

    // 4. Rebuild services index for this namespace (not public)
    try {
      rebuildServicesIndex(nacosNamespace);
    } catch (RuntimeException failure) {
      // The transaction will roll back the new row; do not leave an unindexed config behind.
      try {
        if (!configCenterService.removeConfig(serviceDataId, nacosNamespace)) {
          log.error("Failed to clean up service config after create failed: {} in {}", serviceDataId, nacosNamespace);
        }
      } catch (Exception cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
        log.error("Failed to clean up service config after create failed: {}", serviceDataId, cleanupFailure);
      }
      throw failure;
    }
    cacheAfterCommit(serviceName, service);

    log.info("Service created successfully: {} (Database + Cache + Nacos)", serviceName);
    return entity;
  }

  /**
   * Update service with dual-write to database and Nacos (per-service format).
   * Description is updated in database only, not pushed to Nacos metadata.
   */
  @Transactional(rollbackFor = Exception.class)
  public ServiceEntity updateService(String serviceName, ServiceDefinition service) {
    if (service == null || serviceName == null || serviceName.isEmpty()) {
      throw new IllegalArgumentException("Invalid service name or definition");
    }

    // Find entity by serviceName
    ServiceEntity entity = serviceRepository.findByServiceName(serviceName);
    if (entity == null) {
      throw new IllegalArgumentException("Service not found: " + serviceName);
    }

    // Ensure serviceId is set (use existing one from entity)
    service.setServiceId(entity.getServiceId());

    // AI services: rotate the API key when a new one is supplied; keep the existing ref otherwise.
    // Must run before metadata serialization so the (possibly new) credentialRef is persisted.
    applyAiCredential(service);

    // Get Nacos namespace from instance
    String nacosNamespace = getNacosNamespace(entity.getInstanceId());

    // STRICT CHECK: Service must have valid instanceId
    // Do NOT allow updating services that would publish to public namespace
    if (nacosNamespace == null || nacosNamespace.isEmpty()) {
        throw new IllegalArgumentException(
            "Service '" + serviceName + "' is not associated with a valid gateway instance. " +
            "instanceId=" + entity.getInstanceId() + " does not have a valid nacosNamespace. " +
            "Please associate this service with a valid gateway instance first.");
    }

    // 1. Update database fields
    log.info("Updating service in database: {}, namespace={}", serviceName, nacosNamespace);
    // Update description
    entity.setDescription(service.getDescription());
    // Update category (NORMAL / AI)
    entity.setCategory(normalizeCategory(service.getCategory()));
    // Update metadata JSON backup
    try {
      String configJson = objectMapper.writeValueAsString(service);
      entity.setMetadata(configJson);
    } catch (Exception e) {
      log.warn("Failed to serialize service config to JSON", e);
    }
    entity = serviceRepository.save(entity);

    // 3. Push to Nacos (direct overwrite, no need to remove first)
    // FIX: Don't remove before publish - this can cause data loss if publish fails
    String serviceDataId = SERVICE_PREFIX + entity.getServiceId();
    
    // Set serviceId in ServiceDefinition for Nacos config
    service.setServiceId(entity.getServiceId());
    
    // Create a copy without description for Nacos
    ServiceDefinition nacosConfig = objectMapper.convertValue(service, ServiceDefinition.class);
    nacosConfig.setDescription(null);
    nacosConfig.setApiKey(null);
    nacosConfig.setName(service.getName());
    nacosConfig.setServiceId(entity.getServiceId());
    nacosConfig.setLoadBalancer(service.getLoadBalancer());
    nacosConfig.setInstances(service.getInstances());
    
    publishServiceOrThrow(serviceDataId, nacosNamespace, nacosConfig);
    log.info("Service updated in Nacos: {} (namespace: {})", serviceDataId, nacosNamespace);
    cacheAfterCommit(serviceName, service);

    log.info("Service updated successfully: {} (Database + Cache + Nacos)", serviceName);
    return entity;
  }

  /**
   * Check if service is referenced by any route.
   * @return list of route names that reference this service
   */
  public List<String> checkServiceUsage(String serviceName) {
    // Find the service entity to get its serviceId
    ServiceEntity serviceEntity = serviceRepository.findByServiceName(serviceName);
    if (serviceEntity == null || serviceEntity.getServiceId() == null) {
      return java.util.Collections.emptyList();
    }
    
    String serviceId = serviceEntity.getServiceId();
    
    // Query all routes from database
    List<RouteEntity> routes = routeRepository.findAll();
    
    // Find routes that reference this service by serviceId
    return routes.stream()
        .filter(route -> {
          // Check if route's metadata contains this serviceId
          String metadata = route.getMetadata();
          if (metadata == null || metadata.isEmpty()) {
            return false;
          }
          
          try {
            // Parse metadata JSON and check uri field
            JsonNode rootNode = objectMapper.readTree(metadata);
            String uri = rootNode.path("uri").asText();
            
            // Check if URI references this service by serviceId (lb://serviceId or static://serviceId)
            return uri != null && (uri.contains("lb://" + serviceId) || uri.contains("static://" + serviceId));
          } catch (Exception e) {
            log.warn("Failed to parse route metadata: {}", metadata, e);
            return false;
          }
        })
        .map(RouteEntity::getRouteName)
        .collect(java.util.stream.Collectors.toList());
  }

  /**
   * Delete service from database and Nacos (per-service format).
   */
  @Transactional(rollbackFor = Exception.class)
  public void deleteService(String serviceName) {
    if (serviceName == null || serviceName.isEmpty()) {
      throw new IllegalArgumentException("Invalid service name");
    }

    ServiceEntity entity = serviceRepository.findByServiceName(serviceName);
    if (entity == null) {
      throw new IllegalArgumentException("Service not found: " + serviceName);
    }

    log.info("Deleting service: {} (DB id: {})", serviceName, entity.getId());

    // Get Nacos namespace from instance
    String nacosNamespace = getNacosNamespace(entity.getInstanceId());

    // 1. Remove from cache first
    serviceCache.remove(serviceName);
    log.info("Service removed from cache: {}", serviceName);

    // 2. Delete from Nacos (remove config using service_id UUID).
    // Throw on failure so the @Transactional delete rolls back: deleting the DB row while the
    // config survives would leave a Nacos orphan the reconcile task cannot clean (it resolves
    // the namespace from the now-gone DB row).
    String serviceDataId = SERVICE_PREFIX + entity.getServiceId();
    boolean removed = configCenterService.removeConfig(serviceDataId, nacosNamespace);
    if (!removed) {
      throw new IllegalStateException(
          "Failed to remove service config from config center: " + serviceDataId
              + " (namespace: " + (nacosNamespace != null ? nacosNamespace : "default") + ")");
    }
    log.info("Service removed from Nacos: {} (namespace: {})", serviceDataId, nacosNamespace);

    // 3. Delete from database
    serviceRepository.delete(entity);
    log.info("Service deleted from database: {}", entity.getId());

    // 4. Rebuild services index for this namespace
    rebuildServicesIndex(nacosNamespace);

    log.info("Service deleted successfully: {} (Database + Cache + Nacos)", serviceName);
  }

  /**
   * Add an instance to a service.
   */
  @Transactional(rollbackFor = Exception.class)
  public ServiceDefinition addInstance(String serviceName, ServiceDefinition.ServiceInstance instance) {
    ServiceDefinition service = serviceCache.get(serviceName);
    if (service == null) {
      throw new IllegalArgumentException("Service not found: " + serviceName);
    }
    
    // Check if instance already exists (by ip:port)
    String instanceKey = instance.getIp() + ":" + instance.getPort();
    for (ServiceDefinition.ServiceInstance existing : service.getInstances()) {
      if ((existing.getIp() + ":" + existing.getPort()).equals(instanceKey)) {
        throw new IllegalArgumentException("Instance already exists: " + instanceKey);
      }
    }
    
    // Add instance
    service.getInstances().add(instance);
    log.info("Added instance {} to service {}", instanceKey, serviceName);
    
    // Persist changes
    ServiceEntity entity = updateService(serviceName, service);
    return toDefinition(entity);
  }

  /**
   * Remove an instance from a service.
   */
  @Transactional(rollbackFor = Exception.class)
  public ServiceDefinition removeInstance(String serviceName, String instanceId) {
    ServiceDefinition service = serviceCache.get(serviceName);
    if (service == null) {
      throw new IllegalArgumentException("Service not found: " + serviceName);
    }
    
    // Find and remove instance
    boolean removed = service.getInstances().removeIf(inst -> {
      String key = inst.getIp() + ":" + inst.getPort();
      return key.equals(instanceId) || 
             (inst.getInstanceId() != null && inst.getInstanceId().equals(instanceId));
    });
    
    if (!removed) {
      throw new IllegalArgumentException("Instance not found: " + instanceId);
    }
    
    log.info("Removed instance {} from service {}", instanceId, serviceName);
    
    // Persist changes
    ServiceEntity entity = updateService(serviceName, service);
    return toDefinition(entity);
  }

  /**
   * Update instance status (enabled/healthy).
   */
  @Transactional(rollbackFor = Exception.class)
  public ServiceDefinition updateInstanceStatus(String serviceName, String instanceId, 
                                                  Boolean enabled, Boolean healthy) {
    ServiceDefinition service = serviceCache.get(serviceName);
    if (service == null) {
      throw new IllegalArgumentException("Service not found: " + serviceName);
    }
    
    // Find instance
    ServiceDefinition.ServiceInstance targetInstance = null;
    for (ServiceDefinition.ServiceInstance inst : service.getInstances()) {
      String key = inst.getIp() + ":" + inst.getPort();
      if (key.equals(instanceId) || 
          (inst.getInstanceId() != null && inst.getInstanceId().equals(instanceId))) {
        targetInstance = inst;
        break;
      }
    }
    
    if (targetInstance == null) {
      throw new IllegalArgumentException("Instance not found: " + instanceId);
    }
    
    // Update status
    if (enabled != null) {
      targetInstance.setEnabled(enabled);
      log.info("Set instance {} enabled={} in service {}", instanceId, enabled, serviceName);
    }
    
    // Note: healthy status is managed by health checker, but we can override it
    // This would typically be done through health sync, not manually
    
    // Persist changes
    ServiceEntity entity = updateService(serviceName, service);
    return toDefinition(entity);
  }

  /**
   * Load all services from database to cache.
   */
  private void loadServicesFromDatabase() {
    log.info("Loading services from database...");
    List<ServiceEntity> entities = serviceRepository.findAll();
    for (ServiceEntity entity : entities) {
      try {
        ServiceDefinition service = toDefinition(entity);
        serviceCache.put(entity.getServiceName(), service);
        log.debug("Loaded service: {}", entity.getServiceName());
      } catch (Exception e) {
        log.error("Failed to convert service entity: {}", entity.getId(), e);
      }
    }
    log.info("Loaded {} services from database", entities.size());
  }

  /**
   * Rebuild services index from database.
   * Only includes ENABLED services since disabled services have no config in Nacos.
   * @param nacosNamespace the Nacos namespace to publish the index to (MUST be valid, not null)
   */
  private void rebuildServicesIndex(String nacosNamespace) {
    // STRICT CHECK: Do NOT allow publishing to public namespace
    if (nacosNamespace == null || nacosNamespace.isEmpty()) {
        log.warn("Skipping services index rebuild for public namespace - services MUST be isolated per-instance");
        return;
    }

    try {
      // Get services for this specific instance namespace only
      List<String> serviceIds = serviceRepository.findByEnabledTrue().stream()
          .filter(entity -> nacosNamespace.equals(getNacosNamespace(entity.getInstanceId())))
          .map(ServiceEntity::getServiceId)
          .collect(Collectors.toList());
      
      // Load current Nacos index to check if update is needed
      List<String> currentNacosIndex = null;
      try {
        currentNacosIndex = configCenterService.getConfig(SERVICES_INDEX, nacosNamespace,
            new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
      } catch (Exception e) {
        log.debug("Nacos services-index does not exist or failed to read: {}", e.getMessage());
      }
      
      // Check if rebuild is actually needed
      boolean needsRebuild = false;
      String reason = "";
      
      if (currentNacosIndex == null) {
        // Case 1: Nacos index doesn't exist - always create it
        needsRebuild = true;
        reason = "Nacos index does not exist";
      } else if (serviceIds.equals(currentNacosIndex)) {
        // Case 2: Both are identical - no need to rebuild
        needsRebuild = false;
        log.debug("DB and Nacos index are identical, skipping rebuild");
      } else {
        // Case 3: Different - trust DB as source of truth
        needsRebuild = true;
        reason = "DB and Nacos index differ (DB has " + serviceIds.size() + " services, Nacos has " + currentNacosIndex.size() + ")";
      }
      
      // Perform rebuild if needed
      if (needsRebuild) {
        log.info("Rebuilding services index for namespace {} (reason: {})...", nacosNamespace, reason);
        if (!configCenterService.publishConfig(SERVICES_INDEX, nacosNamespace, serviceIds)) {
          throw new IllegalStateException("Failed to publish services index to namespace: " + nacosNamespace);
        }
        log.info("Services index rebuilt with {} services for namespace {}", serviceIds.size(), nacosNamespace);
      }
      
    } catch (Exception e) {
      log.error("Failed to rebuild services index", e);
      throw new IllegalStateException("Failed to rebuild services index for namespace: " + nacosNamespace, e);
    }
  }

  private void publishServiceOrThrow(String dataId, String namespace, ServiceDefinition service) {
    if (!configCenterService.publishConfig(dataId, namespace, service)) {
      throw new IllegalStateException("Failed to publish service config: " + dataId + " (namespace: " + namespace + ")");
    }
  }

  private void cacheAfterCommit(String serviceName, ServiceDefinition service) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          serviceCache.put(serviceName, service);
        }
      });
    } else {
      serviceCache.put(serviceName, service);
    }
  }

  /**
   * Rebuild services index for all instances' namespaces.
   * Called on startup to ensure each instance's namespace has correct index.
   */
  private void rebuildServicesIndexForAllInstances() {
    // Get all unique instance IDs from services
    List<ServiceEntity> allServices = serviceRepository.findByEnabledTrue();
    Map<String, List<String>> servicesByNamespace = new HashMap<>();

    for (ServiceEntity entity : allServices) {
      String nacosNamespace = getNacosNamespace(entity.getInstanceId());
      String nsKey = (nacosNamespace == null || nacosNamespace.isEmpty()) ? "public" : nacosNamespace;
      servicesByNamespace.computeIfAbsent(nsKey, k -> new ArrayList<>())
          .add(entity.getServiceId());
    }

    // Rebuild index for each namespace
    for (Map.Entry<String, List<String>> entry : servicesByNamespace.entrySet()) {
      String namespace = entry.getKey();
      List<String> serviceIds = entry.getValue();

      // Skip public namespace - we don't want to publish there anymore
      if ("public".equals(namespace)) {
        log.info("Skipping public namespace for services index (per-instance isolation)");
        continue;
      }

      try {
        configCenterService.publishConfig(SERVICES_INDEX, namespace, serviceIds);
        log.info("Rebuilt services index for namespace {} with {} services", namespace, serviceIds.size());
      } catch (Exception e) {
        log.error("Failed to rebuild services index for namespace {}", namespace, e);
      }
    }
  }

  /**
   * AI services hold their upstream API key encrypted in provider_credentials, never in the
   * service metadata. When a plaintext {@code apiKey} is supplied it is encrypted (created or
   * rotated) and only the resulting {@code credentialRef} is kept on the definition. A normal
   * service, or an AI service edited without a new key, keeps whatever ref it already has.
   */
  private void applyAiCredential(ServiceDefinition service) {
    if (service == null) {
      return;
    }
    if (!service.isAiService()) {
      // Never let a plaintext key linger on a non-AI service.
      service.setApiKey(null);
      return;
    }
    if (!hasText(service.getProviderType())) {
      throw new IllegalArgumentException("AI 服务必须指定 provider 类型 (providerType)");
    }

    String apiKey = service.getApiKey();
    if (hasText(apiKey)) {
      boolean rotate = hasText(service.getCredentialRef());
      String ref = rotate ? service.getCredentialRef() : "svc-" + service.getServiceId();
      if (rotate) {
        providerCredentialService.update(ref, service.getName(), apiKey, null, null, null);
      } else {
        providerCredentialService.create(ref, service.getName(), service.getProviderType(), apiKey, null, null);
      }
      service.setCredentialRef(ref);
      log.info("AI service '{}' credential {} (provider={}, ref={})",
          service.getName(), rotate ? "rotated" : "created", service.getProviderType(), ref);
    } else if (!hasText(service.getCredentialRef())) {
      throw new IllegalArgumentException("AI 服务必须提供 API Key");
    }

    // Plaintext is transient and must never be serialized into metadata.
    service.setApiKey(null);
  }

  /** Normalize a service category to NORMAL/AI, defaulting blank/unknown to NORMAL. */
  private String normalizeCategory(String category) {
    return ServiceDefinition.ServiceCategory.AI.equalsIgnoreCase(category)
        ? ServiceDefinition.ServiceCategory.AI
        : ServiceDefinition.ServiceCategory.NORMAL;
  }

  private static boolean hasText(String s) {
    return s != null && !s.trim().isEmpty();
  }

  /**
   * Convert ServiceDefinition to ServiceEntity.
   * Stores complete configuration as JSON for backup purposes.
   */
  private ServiceEntity toEntity(ServiceDefinition service) {    ServiceEntity entity = new ServiceEntity();
    // Don't set name field, use service_name instead
    // entity.setName(...) - removed

    // Set enabled to true by default for new services
    entity.setEnabled(true);

    // Set category (NORMAL / AI)
    entity.setCategory(normalizeCategory(service.getCategory()));

    // Set description
    entity.setDescription(service.getDescription());
    
    // Set serviceId in ServiceDefinition BEFORE serialization
    // This ensures metadata JSON contains the correct serviceId
    if (service.getServiceId() == null && service.getName() != null) {
      // Will be set by caller after this method returns
      // Just a placeholder - actual UUID will be set later
      log.debug("Preparing entity for service: {} (serviceId will be set after UUID generation)", service.getName());
    }
    
    // Store complete configuration as JSON in metadata field for backup
    try {
      String configJson = objectMapper.writeValueAsString(service);
      entity.setMetadata(configJson);
    } catch (Exception e) {
      log.warn("Failed to serialize service config to JSON", e);
    }
    
    return entity;
  }

  /**
   * Convert ServiceEntity to ServiceDefinition.
   * Restores complete configuration from JSON backup.
   */
  private ServiceDefinition toDefinition(ServiceEntity entity) {
    // Try to restore from JSON backup first
    if (entity.getMetadata() != null && !entity.getMetadata().isEmpty()) {
      try {
        ServiceDefinition service = objectMapper.readValue(entity.getMetadata(), ServiceDefinition.class);
        if (service != null) {
          // Merge description from entity if not in JSON (for backward compatibility)
          if (service.getDescription() == null && entity.getDescription() != null) {
            service.setDescription(entity.getDescription());
          }
          return service;
        }
      } catch (Exception e) {
        log.warn("Failed to deserialize service config from JSON, using fallback", e);
      }
    }
    
    // Fallback: create minimal definition
    ServiceDefinition service = new ServiceDefinition();
    service.setName(entity.getServiceName());
    service.setDescription(entity.getDescription());
    return service;
  }

  /**
   * Convert object to JSON string.
   */
  private String toJson(Object obj) {
    try {
      return objectMapper.writeValueAsString(obj);
    } catch (Exception e) {
      log.error("Error converting object to JSON", e);
      return null;
    }
  }

  /**
   * Sync services from database to Nacos on startup.
   * This ensures all services in database have corresponding config in Nacos.
   * Only syncs services that don't exist in Nacos (avoid unnecessary updates).
   */
  private void syncServicesToNacosOnStartup() {
    log.info("🔄 Checking service sync status on startup...");
    
    List<ServiceEntity> allServices = serviceRepository.findByEnabledTrue();
    int syncedCount = 0;
    int skippedCount = 0;
    
    for (ServiceEntity entity : allServices) {
      try {
        String serviceName = entity.getServiceName();
        String serviceId = entity.getServiceId();
        String nacosNamespace = getNacosNamespace(entity.getInstanceId());
        String serviceDataId = SERVICE_PREFIX + serviceId;
        
        // STRICT CHECK: Skip services without valid namespace (invalid instanceId)
        if (nacosNamespace == null || nacosNamespace.isEmpty()) {
            log.warn("Skipping service '{}' (serviceId={}) - no valid instanceId/namespace. " +
                     "instanceId={} - Service MUST be associated with a gateway instance.",
                     serviceName, serviceId, entity.getInstanceId());
            continue;
        }
        
        // Check if config already exists in Nacos
        String existingConfig = configCenterService.getConfig(serviceDataId, nacosNamespace, String.class);
        
        if (existingConfig == null || existingConfig.isEmpty()) {
          // Config missing in Nacos, need to sync
          log.info("⚠️ Service config missing in Nacos: {} (dataId: {})", serviceName, serviceDataId);
          
          ServiceDefinition definition = toDefinition(entity);
          if (definition == null) {
            log.warn("Failed to convert entity to definition: {}", serviceName);
            continue;
          }
          definition.setServiceId(serviceId);
          
          // Create a copy without description for Nacos
          ServiceDefinition nacosConfig = new ServiceDefinition();
          nacosConfig.setName(definition.getName());
          nacosConfig.setServiceId(serviceId);
          nacosConfig.setLoadBalancer(definition.getLoadBalancer());
          nacosConfig.setInstances(definition.getInstances());
          
          configCenterService.publishConfig(serviceDataId, nacosNamespace, nacosConfig);
          log.info("✅ Synced service to Nacos: {} (namespace: {})", serviceName, nacosNamespace);
          syncedCount++;
        } else {
          skippedCount++;
          log.debug("Service config exists in Nacos: {} (skipped)", serviceName);
        }
        
      } catch (Exception e) {
        log.error("Failed to sync service on startup: {}", entity.getServiceName(), e);
      }
    }
    
    log.info("✅ Startup sync completed: {} synced, {} already existed", syncedCount, skippedCount);
  }

  /**
   * Sync all services from database to Nacos.
   * This is a repair function to fix data inconsistency issues.
   * @return sync result with count of synced services
   */
  @Transactional(rollbackFor = Exception.class)
  public Map<String, Object> syncAllServicesToNacos() {
    log.info("🔄 Starting to sync all services from database to Nacos...");
    
    List<ServiceEntity> allServices = serviceRepository.findAll();
    int successCount = 0;
    int failCount = 0;
    List<String> failedServices = new ArrayList<>();
    
    for (ServiceEntity entity : allServices) {
      try {
        String serviceName = entity.getServiceName();
        String serviceId = entity.getServiceId();
        String nacosNamespace = getNacosNamespace(entity.getInstanceId());
        
        // Skip disabled services (they shouldn't have config in Nacos)
        if (!Boolean.TRUE.equals(entity.getEnabled())) {
          log.debug("Skipping disabled service: {}", serviceName);
          continue;
        }
        
        // STRICT CHECK: Skip services without valid namespace (invalid instanceId)
        if (nacosNamespace == null || nacosNamespace.isEmpty()) {
          log.warn("Skipping service '{}' (serviceId={}) - no valid instanceId/namespace. " +
                   "instanceId={} - Service MUST be associated with gateway instance.",
                   serviceName, serviceId, entity.getInstanceId());
          failCount++;
          failedServices.add(serviceName + " (no valid instanceId)");
          continue;
        }
        
        // Convert entity to definition
        ServiceDefinition definition = toDefinition(entity);
        if (definition == null) {
          log.warn("Failed to convert entity to definition: {}", serviceName);
          failCount++;
          failedServices.add(serviceName + " (conversion failed)");
          continue;
        }
        
        definition.setServiceId(serviceId);
        
        // Push to Nacos
        String serviceDataId = SERVICE_PREFIX + serviceId;
        
        // Create a copy without description for Nacos
        ServiceDefinition nacosConfig = new ServiceDefinition();
        nacosConfig.setName(definition.getName());
        nacosConfig.setServiceId(serviceId);
        nacosConfig.setLoadBalancer(definition.getLoadBalancer());
        nacosConfig.setInstances(definition.getInstances());
        
        configCenterService.publishConfig(serviceDataId, nacosNamespace, nacosConfig);
        log.info("✅ Synced service to Nacos: {} (namespace: {}, dataId: {})", 
            serviceName, nacosNamespace, serviceDataId);
        successCount++;
        
      } catch (Exception e) {
        log.error("❌ Failed to sync service: {}", entity.getServiceName(), e);
        failCount++;
        failedServices.add(entity.getServiceName() + " (" + e.getMessage() + ")");
      }
    }
    
    // Rebuild services index after sync
    rebuildServicesIndexForAllInstances();
    
    Map<String, Object> result = new HashMap<>();
    result.put("total", allServices.size());
    result.put("enabledCount", allServices.stream().filter(e -> Boolean.TRUE.equals(e.getEnabled())).count());
    result.put("syncedCount", successCount);
    result.put("failedCount", failCount);
    result.put("failedServices", failedServices);
    
    log.info("✅ Service sync completed: {} synced, {} failed out of {} enabled services", 
        successCount, failCount, allServices.stream().filter(e -> Boolean.TRUE.equals(e.getEnabled())).count());
    
    return result;
  }
}
