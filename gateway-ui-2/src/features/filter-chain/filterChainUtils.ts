/**
 * Filter-chain formatting, export and waterfall geometry helpers.
 * Ported from gateway-ui/src/features/filter-chain/index.tsx (CSV/JSON export)
 * and components/WaterfallChart.tsx (cumulative-start geometry, time formatting).
 */
import type { FilterExecution, FilterStats } from '@/api/filterChain'

/** Precise self time in ms: μs precision wins over the rounded ms field. */
export function selfTimeMs(exec: FilterExecution): number {
  if (exec.selfTimeMicros != null) return exec.selfTimeMicros / 1000
  return exec.selfTimeMs ?? 0
}

/** μs below 0.01ms, 3 decimals below 1ms, 2 decimals otherwise (React parity). */
export function formatSelfTime(exec: FilterExecution): string {
  const precise = selfTimeMs(exec)
  const value = precise > 0 ? precise : (exec.selfTimeMs ?? 0)
  if (value < 0.01) {
    const micros = exec.selfTimeMicros ?? (exec.selfTimeMs ?? 0) * 1000
    return `${micros.toFixed(0)}μs`
  }
  if (value < 1) return `${value.toFixed(3)}ms`
  return `${value.toFixed(2)}ms`
}

/** Duration cell: switch μs→ms above 1000μs (React parity). */
export function formatMicros(micros: number): string {
  return micros > 1000 ? `${(micros / 1000).toFixed(2)}ms` : `${micros.toFixed(2)}μs`
}

export type WaterfallHealth = 'normal' | 'warning' | 'error'

export interface WaterfallRow {
  exec: FilterExecution
  index: number
  selfMs: number
  downstreamMs: number
  startMs: number
  startPercent: number
  selfPercent: number
  downstreamPercent: number
  /** Self share inside the bar (0-100). */
  selfShare: number
  downstreamShare: number
  barWidthPercent: number
  health: WaterfallHealth
  timeDisplay: string
}

/**
 * Waterfall geometry (React parity): rows sorted by order, each bar starts at
 * the cumulative self time of all previous filters; bar width = self + downstream.
 */
export function buildWaterfallRows(
  executions: FilterExecution[],
  totalDurationMs: number,
  slowThresholdMs: number,
): WaterfallRow[] {
  const sorted = [...executions].sort((a, b) => a.order - b.order)
  let cursor = 0
  return sorted.map((exec, index) => {
    const selfMs = selfTimeMs(exec)
    const downstreamMs = exec.downstreamMs ?? 0
    const startMs = cursor
    cursor += selfMs

    const total = totalDurationMs > 0 ? totalDurationMs : 0
    const rawStart = total > 0 ? (startMs / total) * 100 : 0
    const selfPercent = total > 0 ? (selfMs / total) * 100 : 0
    const downstreamPercent = total > 0 ? (downstreamMs / total) * 100 : 0
    const startPercent = Math.min(rawStart, 99.5)
    const barWidthPercent = Math.max(Math.min(selfPercent + downstreamPercent, 100 - startPercent), 0.5)
    const segmentSum = selfPercent + downstreamPercent
    const selfShare = segmentSum > 0 ? (selfPercent / segmentSum) * 100 : 100

    const health: WaterfallHealth = !exec.success ? 'error' : selfMs > slowThresholdMs ? 'warning' : 'normal'
    return {
      exec,
      index,
      selfMs,
      downstreamMs,
      startMs,
      startPercent,
      selfPercent,
      downstreamPercent,
      selfShare,
      downstreamShare: 100 - selfShare,
      barWidthPercent,
      health,
      timeDisplay: formatSelfTime(exec),
    }
  })
}

/** CSV export — same headers/rows as the React page. */
export function statsToCsv(stats: FilterStats[]): string {
  const headers = [
    'Filter Name', 'Order', 'Total Count', 'Success Rate', 'Avg Self Time (ms)',
    'P95 (ms)', 'P99 (ms)', 'Min Self Time (μs)', 'Max Self Time (μs)',
  ]
  const rows = stats.map((stat) => [
    stat.filterName,
    stat.order,
    stat.totalCount,
    stat.successRate,
    stat.avgSelfTimeMsRaw.toFixed(3),
    (stat.selfP95Micros / 1000).toFixed(2),
    (stat.selfP99Micros / 1000).toFixed(2),
    stat.minSelfTimeMicros,
    stat.maxSelfTimeMicros,
  ])
  return [headers.join(','), ...rows.map((row) => row.join(','))].join('\n')
}

/** Trigger a browser download for an in-memory blob (React downloadBlob parity). */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.style.display = 'none'
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  setTimeout(() => {
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
  }, 100)
}

function escapeXml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

export interface WaterfallSvgTexts {
  legendNormal: string
  legendSlow: string
  legendError: string
  legendDownstream: string
  totalTime: string
}

const HEALTH_FILL: Record<WaterfallHealth, string> = {
  normal: 'url(#fcwGrad)',
  warning: '#eab308',
  error: '#ef4444',
}

/**
 * Render the waterfall as a standalone SVG string for PNG export. Shares the
 * exact geometry of the DOM chart (same WaterfallRow[]); the page itself never
 * renders canvas — an offscreen canvas is only used at export time.
 */
export function buildWaterfallSvg(
  rows: WaterfallRow[],
  totalDurationMs: number,
  texts: WaterfallSvgTexts,
): { svg: string; width: number; height: number } {
  const width = 960
  const pad = 16
  const nameW = 210
  const rightW = 118
  const trackX = pad + nameW
  const trackW = width - pad * 2 - nameW - rightW
  const rowH = 30
  const axisH = 26
  const legendH = 34
  const height = pad * 2 + axisH + rows.length * rowH + legendH

  const parts: string[] = []
  parts.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" font-family="Inter, 'Segoe UI', sans-serif">`)
  parts.push(`<defs><linearGradient id="fcwGrad" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#6998ef"/><stop offset="1" stop-color="#a492ea"/></linearGradient></defs>`)
  parts.push(`<rect width="${width}" height="${height}" rx="12" fill="#0c1526"/>`)

  for (const percent of [0, 25, 50, 75, 100]) {
    const x = trackX + (trackW * percent) / 100
    const anchor = percent === 0 ? 'start' : percent === 100 ? 'end' : 'middle'
    parts.push(`<text x="${x}" y="${pad + 10}" font-size="10" fill="#6f81a0" text-anchor="${anchor}">${((totalDurationMs * percent) / 100).toFixed(1)}ms</text>`)
  }

  rows.forEach((row, i) => {
    const y = pad + axisH + i * rowH
    const barY = y + 6
    const barH = 16
    const name = row.exec.filter.length > 26 ? `${row.exec.filter.slice(0, 25)}…` : row.exec.filter
    parts.push(`<rect x="${pad}" y="${barY}" width="22" height="16" rx="8" fill="rgba(127,157,255,0.16)"/>`)
    parts.push(`<text x="${pad + 11}" y="${barY + 12}" font-size="10" font-weight="600" fill="#bcd0ff" text-anchor="middle">${row.exec.order}</text>`)
    parts.push(`<text x="${pad + 30}" y="${barY + 12}" font-size="11" fill="#c2cde0">${escapeXml(name)}</text>`)
    parts.push(`<rect x="${trackX}" y="${barY}" width="${trackW}" height="${barH}" rx="5" fill="rgba(255,255,255,0.035)"/>`)

    const barX = trackX + (trackW * row.startPercent) / 100
    const barW = Math.max((trackW * row.barWidthPercent) / 100, 3)
    const selfW = (barW * row.selfShare) / 100
    parts.push(`<rect x="${barX}" y="${barY}" width="${selfW}" height="${barH}" rx="4" fill="${HEALTH_FILL[row.health]}"/>`)
    if (row.downstreamShare > 0 && row.downstreamMs > 0) {
      parts.push(`<rect x="${barX + selfW}" y="${barY}" width="${barW - selfW}" height="${barH}" fill="rgba(79,214,230,0.45)"/>`)
    }
    if (row.selfPercent > 3 && row.selfMs > 0.5 && selfW > 34) {
      parts.push(`<text x="${barX + selfW / 2}" y="${barY + 12}" font-size="9" fill="#f4f7ff" text-anchor="middle">${row.selfMs.toFixed(1)}ms</text>`)
    }
    parts.push(`<text x="${width - pad - 26}" y="${barY + 12}" font-size="10" fill="#a5b4cb" text-anchor="end">${escapeXml(row.timeDisplay)}</text>`)
    parts.push(`<circle cx="${width - pad - 10}" cy="${barY + 8}" r="5" fill="${row.exec.success ? '#22c55e' : '#ef4444'}"/>`)
  })

  const legendY = height - pad - 10
  const legends: Array<[string, string]> = [
    [texts.legendNormal, '#6998ef'],
    [texts.legendSlow, '#eab308'],
    [texts.legendError, '#ef4444'],
    [texts.legendDownstream, 'rgba(79,214,230,0.45)'],
  ]
  let legendX = pad
  for (const [label, fill] of legends) {
    parts.push(`<rect x="${legendX}" y="${legendY - 9}" width="10" height="10" rx="3" fill="${fill}"/>`)
    parts.push(`<text x="${legendX + 16}" y="${legendY}" font-size="11" fill="#8fa1c0">${escapeXml(label)}</text>`)
    legendX += 16 + label.length * 11 + 24
  }
  parts.push(`<text x="${width - pad}" y="${legendY}" font-size="11" fill="#8fa1c0" text-anchor="end">${escapeXml(texts.totalTime)}</text>`)
  parts.push('</svg>')
  return { svg: parts.join(''), width, height }
}

/** Rasterize an SVG string to a PNG blob via a transient offscreen canvas. */
export async function svgToPngBlob(svg: string, width: number, height: number): Promise<Blob> {
  const image = new Image()
  const loaded = new Promise<void>((resolve, reject) => {
    image.onload = () => resolve()
    image.onerror = () => reject(new Error('SVG rasterization failed'))
  })
  image.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
  await loaded
  const canvas = document.createElement('canvas')
  canvas.width = width * 2
  canvas.height = height * 2
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('Canvas 2D unavailable')
  ctx.scale(2, 2)
  ctx.drawImage(image, 0, 0)
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'))
  if (!blob) throw new Error('PNG encoding failed')
  return blob
}