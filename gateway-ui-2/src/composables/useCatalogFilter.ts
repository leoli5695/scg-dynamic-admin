/**
 * Shared catalog filtering for list pages (services today, routes next).
 * Owns search text, a category segment, a health filter and the derived
 * counts, so pages do not each re-implement the same computed chain.
 */
import { computed, ref, type ComputedRef, type Ref } from 'vue'

export type HealthFilter = 'all' | 'healthy' | 'unhealthy'

export interface CatalogFilterOptions<T> {
  items: Ref<T[]> | ComputedRef<T[]>
  /** Text used by the search box; return the searchable string for an item. */
  searchText: (item: T) => string
  isAi: (item: T) => boolean
  isHealthy: (item: T) => boolean
}

export function useCatalogFilter<T>({ items, searchText, isAi, isHealthy }: CatalogFilterOptions<T>) {
  const search = ref('')
  const category = ref<'ALL' | 'NORMAL' | 'AI'>('ALL')
  const health = ref<HealthFilter>('all')

  const categoryCounts = computed(() => {
    const ai = items.value.filter(isAi).length
    return { all: items.value.length, ai, normal: items.value.length - ai }
  })

  const normalItems = computed(() => items.value.filter((i) => !isAi(i)))
  const healthyCount = computed(() => normalItems.value.filter(isHealthy).length)
  const unhealthyCount = computed(() => normalItems.value.length - healthyCount.value)

  const filtered = computed(() => {
    const needle = search.value.trim().toLowerCase()
    return items.value.filter((item) => {
      if (needle && !searchText(item).toLowerCase().includes(needle)) return false
      if (category.value !== 'ALL') {
        const ai = isAi(item)
        if (category.value === 'AI' ? !ai : ai) return false
      }
      // AI services have no instance pool, so health does not apply to them.
      if (health.value !== 'all' && !isAi(item)) {
        const ok = isHealthy(item)
        if (health.value === 'healthy' ? !ok : ok) return false
      }
      return true
    })
  })

  const isFiltered = computed(
    () => search.value.trim() !== '' || category.value !== 'ALL' || health.value !== 'all',
  )

  function reset() {
    search.value = ''
    category.value = 'ALL'
    health.value = 'all'
  }

  return {
    search,
    category,
    health,
    filtered,
    categoryCounts,
    normalItems,
    healthyCount,
    unhealthyCount,
    isFiltered,
    reset,
  }
}