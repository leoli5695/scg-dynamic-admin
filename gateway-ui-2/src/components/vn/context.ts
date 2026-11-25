import type { InjectionKey } from 'vue'

/** Nesting depth of the surrounding VnPanel; drives the nested-surface step. */
export const VN_PANEL_DEPTH: InjectionKey<number> = Symbol('vn-panel-depth')