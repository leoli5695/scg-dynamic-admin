package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.DistributedTraceEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 分布式链路追踪Repository
 * 
 * @author leoli
 */
@Repository
public interface DistributedTraceRepository extends JpaRepository<DistributedTraceEntity, Long> {

    /**
     * 根据TraceId查询（单条）
     */
    Optional<DistributedTraceEntity> findByTraceId(String traceId);

    /**
     * 根据TraceId查询所有匹配记录（一个请求可能经过多个下游服务）
     */
    List<DistributedTraceEntity> findAllByTraceId(String traceId);

    /**
     * 批量根据TraceId查询（全链路列表页使用）
     */
    List<DistributedTraceEntity> findByTraceIdIn(List<String> traceIds);

    /**
     * 根据服务名称查询
     */
    List<DistributedTraceEntity> findByServiceName(String serviceName);

    /**
     * 根据服务名称分页查询
     */
    Page<DistributedTraceEntity> findByServiceName(String serviceName, Pageable pageable);

    /**
     * 查询慢请求
     */
    List<DistributedTraceEntity> findByIsSlowTrue();

    /**
     * 分页查询慢请求
     */
    List<DistributedTraceEntity> findByIsSlowTrue(Pageable pageable);

    /**
     * 查询失败的请求
     */
    List<DistributedTraceEntity> findBySuccessFalse();

    /**
     * 分页查询失败请求
     */
    List<DistributedTraceEntity> findBySuccessFalse(Pageable pageable);

    /**
     * 分页查询服务的慢请求
     */
    List<DistributedTraceEntity> findByServiceNameAndIsSlowTrue(String serviceName, Pageable pageable);

    /**
     * 分页查询服务的失败请求
     */
    List<DistributedTraceEntity> findByServiceNameAndSuccessFalse(String serviceName, Pageable pageable);

    /**
     * 查询指定时间范围内的Trace
     */
    @Query("SELECT t FROM DistributedTraceEntity t WHERE t.traceTime BETWEEN :start AND :end ORDER BY t.traceTime DESC")
    List<DistributedTraceEntity> findByTimeRange(LocalDateTime start, LocalDateTime end);

    /**
     * 查询指定服务和时间范围的Trace
     */
    @Query("SELECT t FROM DistributedTraceEntity t WHERE t.serviceName = :serviceName AND t.traceTime BETWEEN :start AND :end ORDER BY t.traceTime DESC")
    List<DistributedTraceEntity> findByServiceNameAndTimeRange(String serviceName, LocalDateTime start, LocalDateTime end);

    /**
     * 统计服务请求数量
     */
    @Query("SELECT COUNT(t) FROM DistributedTraceEntity t WHERE t.serviceName = :serviceName")
    long countByServiceName(String serviceName);

    /**
     * 统计慢请求数量
     */
    long countByServiceNameAndIsSlowTrue(String serviceName);

    /**
     * 统计失败请求数量
     */
    long countByServiceNameAndSuccessFalse(String serviceName);

    /**
     * 查询最近N分钟的Trace
     */
    @Query("SELECT t FROM DistributedTraceEntity t WHERE t.traceTime > :since ORDER BY t.traceTime DESC")
    List<DistributedTraceEntity> findRecentTraces(LocalDateTime since);

    /**
     * 查询平均耗时
     */
    @Query("SELECT AVG(t.totalDurationMs) FROM DistributedTraceEntity t WHERE t.serviceName = :serviceName")
    Double findAverageDuration(String serviceName);

    /**
     * 查询P99耗时（简化版本）
     */
    @Query(value = "SELECT total_duration_ms FROM distributed_trace WHERE service_name = ?1 ORDER BY total_duration_ms DESC LIMIT 1 OFFSET CAST((SELECT COUNT(*) FROM distributed_trace WHERE service_name = ?1) * 0.01 AS INT)", nativeQuery = true)
    Optional<Long> findP99Duration(String serviceName);

    /**
     * 删除指定时间之前的Trace
     */
    int deleteByTraceTimeBefore(LocalDateTime before);

    /**
     * 查询超过阈值的慢请求（AI 分析专用）
     */
    List<DistributedTraceEntity> findByServiceNameAndTotalDurationMsGreaterThan(
        String serviceName, 
        Long thresholdMs, 
        Pageable pageable
    );
}