package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.ConfigTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConfigTemplateRepository extends JpaRepository<ConfigTemplate, Long> {

    List<ConfigTemplate> findByCategory(String category);

    List<ConfigTemplate> findBySubcategory(String subcategory);

    List<ConfigTemplate> findByIsOfficialTrue();

    List<ConfigTemplate> findByIsPublicTrue();

    List<ConfigTemplate> findByCreatedBy(String createdBy);

    @Query("SELECT t FROM ConfigTemplate t WHERE t.isPublic = true ORDER BY t.downloadCount DESC")
    List<ConfigTemplate> findPopularTemplates();

    @Query("SELECT t FROM ConfigTemplate t WHERE t.isPublic = true ORDER BY t.likeCount DESC")
    List<ConfigTemplate> findTopRatedTemplates();

    @Query("SELECT t FROM ConfigTemplate t WHERE t.isPublic = true AND t.category = :category ORDER BY t.downloadCount DESC")
    List<ConfigTemplate> findPopularByCategory(String category);

    @Query("SELECT DISTINCT t.category FROM ConfigTemplate t WHERE t.isPublic = true")
    List<String> findAllCategories();

    @Query("SELECT DISTINCT t.subcategory FROM ConfigTemplate t WHERE t.isPublic = true AND t.category = :category")
    List<String> findSubcategoriesByCategory(String category);

    @Query("SELECT t FROM ConfigTemplate t WHERE t.isPublic = true AND " +
           "(LOWER(t.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(t.description) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(t.tags) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    List<ConfigTemplate> searchTemplates(String keyword);

    @Query("SELECT t FROM ConfigTemplate t WHERE t.category = :category AND " +
           "(:subcategory IS NULL OR t.subcategory = :subcategory) AND " +
           "(:officialOnly IS NULL OR t.isOfficial = :officialOnly) AND t.isPublic = true")
    List<ConfigTemplate> filterTemplates(String category, String subcategory, Boolean officialOnly);
}