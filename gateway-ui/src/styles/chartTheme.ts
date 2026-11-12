import { chartAxis, palette } from './palette';

/**
 * Shared echarts fragments. Every chart in the console draws its axes, grid and
 * tooltips from here so a series never sits on chrome from a different palette.
 */

export const chartTooltip = {
  backgroundColor: chartAxis.tooltipBg,
  borderColor: chartAxis.tooltipBorder,
  borderWidth: 1,
  padding: [8, 12] as [number, number],
  textStyle: { color: chartAxis.tooltipText, fontSize: 12 },
  extraCssText: 'border-radius:10px;box-shadow:0 12px 28px rgba(4,7,15,0.46);',
};

/** Category axis: no tick marks, one hairline, muted labels. */
export const chartCategoryAxis = {
  axisLine: { lineStyle: { color: chartAxis.line } },
  axisTick: { show: false },
  axisLabel: { color: chartAxis.label, fontSize: 11 },
  splitLine: { show: false },
};

/** Value axis: horizontal hairlines only, so the series stays the loudest thing. */
export const chartValueAxis = {
  axisLine: { show: false },
  axisTick: { show: false },
  axisLabel: { color: chartAxis.label, fontSize: 11 },
  splitLine: { lineStyle: { color: chartAxis.grid } },
};

export const chartLegend = {
  textStyle: { color: chartAxis.label, fontSize: 12 },
  inactiveColor: palette.textDisabled,
  itemWidth: 12,
  itemHeight: 8,
  icon: 'roundRect',
};

export const chartTitle = { color: palette.textPrimary, fontSize: 14, fontWeight: 600 as const };

/** Theme for the @nivo charts, which take a nested theme object instead. */
export const nivoTheme = {
  background: 'transparent',
  text: { fill: chartAxis.label, fontSize: 11 },
  axis: {
    domain: { line: { stroke: chartAxis.line, strokeWidth: 1 } },
    ticks: {
      line: { stroke: chartAxis.line, strokeWidth: 1 },
      text: { fill: chartAxis.label, fontSize: 11 },
    },
    legend: { text: { fill: palette.textTertiary, fontSize: 12 } },
  },
  grid: { line: { stroke: chartAxis.grid, strokeWidth: 1 } },
  crosshair: { line: { stroke: palette.borderStrong, strokeWidth: 1 } },
  tooltip: {
    container: {
      background: chartAxis.tooltipBg,
      color: chartAxis.tooltipText,
      fontSize: 12,
      borderRadius: 10,
      boxShadow: '0 12px 28px rgba(4, 7, 15, 0.46)',
    },
  },
};