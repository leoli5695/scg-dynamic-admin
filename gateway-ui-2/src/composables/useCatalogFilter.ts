/**
 * Shared catalog filtering for list pages (services today, routes next).
 * The category segment is exclusive (no "all"): health semantics and the metric
 * rail are scoped to whichever category is selected.
 */
import { computed, ref, watch, type ComputedRef, type Ref } from 'vue'

export type CatalogCategory = 'NORMAL' | 'AI'
export type HealthFilter = 'all' | 'healthy' | 'unhealthy' | 'unknown'

export interface CatalogFilterOptions<T> {
  items: Ref<T[]> | ComputedRef<T[]>
  searchText: (item: T) => string
  categoryOf: (item: T) => CatalogCategory
  /** Per-category health source; see services/utils.catalogHealth. */
  healthOf: (item: T) => 'healthy' | 'unhealthy' | 'unknown'
}

export function useCatalogFilter<T>({ items, searchText, categoryOf, healthOf }: CatalogFilterOptions<T>) {
  const search = ref('')
  const category = ref<CatalogCategory>('NORMAL')
  const health = ref<HealthFilter>('all')

  const categoryCounts = computed(() => {
    let normal = 0
    let ai = 0
    for (const item of items.value) {
      if (categoryOf(item) === 'AI') ai++
      else normal++
    }
    return { normal, ai }
  })

  const inCategory = computed(() => items.value.filter((i) => categoryOf(i) === category.value))

  const healthCounts = computed(() => {
    const counts = { total: inCategory.value.length, healthy: 0, unhealthy: 0, unknown: 0 }
    for (const item of inCategory.value) counts[healthOf(item)]++
    return counts
  })

  const filtered = computed(() => {
    const needle = search.value.trim().toLowerCase()
    return inCategory.value.filter((item) => {
      if (needle && !searchText(item).toLowerCase().includes(needle)) return false
      if (health.value !== 'all' && healthOf(item) !== health.value) return false
      return true
    })
  })

  const isFiltered = computed(() => search.value.trim() !== '' || health.value !== 'all')

  // "unknown" only exists for AI, so a category switch must not keep a filter
  // that can never match.
  watch(category, () => { health.value = 'all' })

  function reset() {
    search.value = ''
    health.value = 'all'
  }

  return {
    search,
    category,
    health,
    filtered,
    categoryCounts,
    healthCounts,
    isFiltered,
    reset,
  }
}