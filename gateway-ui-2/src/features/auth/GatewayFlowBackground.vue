<script setup lang="ts">
/**
 * GatewayFlowBackground — traffic converging on a gateway: perspective floor,
 * bezier flow lines arcing in from the edges, glowing packets riding them and
 * bursting at the core. Faithful Vue port of the legacy canvas component.
 */
import { onMounted, onUnmounted, ref, watch } from 'vue'

interface Props {
  curveCount?: number
  packetsPerCurve?: number
  opacity?: number
}
const props = withDefaults(defineProps<Props>(), {
  curveCount: 9,
  packetsPerCurve: 3,
  opacity: 0.85,
})

interface Curve {
  p0: [number, number]
  p1: [number, number]
  p2: [number, number]
  p3: [number, number]
  hue: number
}
interface Packet {
  curve: number
  t: number
  speed: number
  size: number
  trail: Array<[number, number]>
}
interface Burst {
  x: number
  y: number
  r: number
  alpha: number
  hue: number
}

const PALETTE: Array<[number, number, number]> = [
  [99, 102, 241],
  [59, 130, 246],
  [45, 212, 191],
  [167, 139, 250],
]

const cubic = (
  t: number,
  a: [number, number],
  b: [number, number],
  c: [number, number],
  d: [number, number],
): [number, number] => {
  const mt = 1 - t
  const x = mt * mt * mt * a[0] + 3 * mt * mt * t * b[0] + 3 * mt * t * t * c[0] + t * t * t * d[0]
  const y = mt * mt * mt * a[1] + 3 * mt * mt * t * b[1] + 3 * mt * t * t * c[1] + t * t * t * d[1]
  return [x, y]
}

const canvasRef = ref<HTMLCanvasElement | null>(null)
let cleanup: (() => void) | null = null

function init() {
  const canvas = canvasRef.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return

  const { curveCount, packetsPerCurve } = props
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const dpr = Math.min(window.devicePixelRatio || 1, 2)

  let w = 0
  let h = 0
  let cx = 0
  let cy = 0
  let curves: Curve[] = []
  let packets: Packet[] = []
  let bursts: Burst[] = []
  let tick = 0

  const resize = () => {
    w = window.innerWidth
    h = window.innerHeight
    cx = w / 2
    cy = h / 2
    canvas.width = w * dpr
    canvas.height = h * dpr
    canvas.style.width = `${w}px`
    canvas.style.height = `${h}px`
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  }

  const makeCurve = (i: number): Curve => {
    const fromLeft = i % 2 === 0
    const startY = h * (0.08 + Math.random() * 0.84)
    const endJx = (Math.random() - 0.5) * 90
    const endJy = (Math.random() - 0.5) * 90
    const p0: [number, number] = fromLeft ? [-40, startY] : [w + 40, startY]
    const p3: [number, number] = [cx + endJx, cy + endJy]
    const bend = (Math.random() - 0.5) * h * 0.5
    const p1: [number, number] = fromLeft
      ? [w * (0.18 + Math.random() * 0.12), startY + bend]
      : [w * (0.70 + Math.random() * 0.12), startY + bend]
    const p2: [number, number] = fromLeft
      ? [w * 0.36, cy + bend * 0.35]
      : [w * 0.64, cy + bend * 0.35]
    return { p0, p1, p2, p3, hue: i % PALETTE.length }
  }

  const seed = () => {
    const n = w < 768 ? Math.max(4, Math.floor(curveCount / 2)) : curveCount
    curves = Array.from({ length: n }, (_, i) => makeCurve(i))
    packets = []
    curves.forEach((_, ci) => {
      for (let k = 0; k < packetsPerCurve; k++) {
        packets.push({
          curve: ci,
          t: Math.random(),
          speed: 0.0016 + Math.random() * 0.0022,
          size: 1.4 + Math.random() * 1.6,
          trail: [],
        })
      }
    })
    bursts = []
  }

  const drawFloor = () => {
    const horizon = h * 0.66
    ctx.save()
    ctx.strokeStyle = 'rgba(99, 130, 255, 0.055)'
    ctx.lineWidth = 1
    for (let i = 0; i < 12; i++) {
      const p = i / 12
      const y = horizon + (h - horizon) * (p * p)
      ctx.globalAlpha = 0.35 + p * 0.65
      ctx.beginPath()
      ctx.moveTo(0, y)
      ctx.lineTo(w, y)
      ctx.stroke()
    }
    ctx.globalAlpha = 0.5
    for (let i = -10; i <= 10; i++) {
      const xBottom = cx + i * (w / 9)
      ctx.beginPath()
      ctx.moveTo(cx + i * 26, horizon)
      ctx.lineTo(xBottom, h + 40)
      ctx.stroke()
    }
    ctx.restore()
  }

  const drawCore = () => {
    const pulse = 0.5 + 0.5 * Math.sin(tick * 0.012)
    const R = Math.min(w, h) * (0.34 + pulse * 0.03)
    const g = ctx.createRadialGradient(cx, cy, 0, cx, cy, R)
    g.addColorStop(0, `rgba(99, 102, 241, ${0.20 + pulse * 0.06})`)
    g.addColorStop(0.45, 'rgba(59, 130, 246, 0.09)')
    g.addColorStop(1, 'rgba(45, 212, 191, 0)')
    ctx.fillStyle = g
    ctx.beginPath()
    ctx.arc(cx, cy, R, 0, Math.PI * 2)
    ctx.fill()
  }

  const drawCurves = () => {
    curves.forEach((c) => {
      const [r, g, b] = PALETTE[c.hue]
      const grad = ctx.createLinearGradient(c.p0[0], c.p0[1], c.p3[0], c.p3[1])
      grad.addColorStop(0, `rgba(${r}, ${g}, ${b}, 0)`)
      grad.addColorStop(0.55, `rgba(${r}, ${g}, ${b}, 0.12)`)
      grad.addColorStop(1, `rgba(${r}, ${g}, ${b}, 0.30)`)
      ctx.strokeStyle = grad
      ctx.lineWidth = 1.1
      ctx.beginPath()
      ctx.moveTo(c.p0[0], c.p0[1])
      ctx.bezierCurveTo(c.p1[0], c.p1[1], c.p2[0], c.p2[1], c.p3[0], c.p3[1])
      ctx.stroke()
    })
  }

  const drawPackets = () => {
    ctx.save()
    ctx.globalCompositeOperation = 'lighter'
    packets.forEach((p) => {
      const c = curves[p.curve]
      if (!c) return
      const [r, g, b] = PALETTE[c.hue]
      const [x, y] = cubic(p.t, c.p0, c.p1, c.p2, c.p3)
      p.trail.unshift([x, y])
      if (p.trail.length > 16) p.trail.pop()

      p.trail.forEach(([tx, ty], i) => {
        const fade = 1 - i / p.trail.length
        ctx.beginPath()
        ctx.arc(tx, ty, p.size * fade, 0, Math.PI * 2)
        ctx.fillStyle = `rgba(${r}, ${g}, ${b}, ${0.30 * fade * fade})`
        ctx.fill()
      })

      ctx.beginPath()
      ctx.arc(x, y, p.size, 0, Math.PI * 2)
      ctx.fillStyle = `rgba(${r}, ${g}, ${b}, 0.95)`
      ctx.shadowBlur = 14
      ctx.shadowColor = `rgba(${r}, ${g}, ${b}, 0.9)`
      ctx.fill()
      ctx.shadowBlur = 0
    })
    ctx.restore()
  }

  const drawBursts = () => {
    ctx.save()
    ctx.globalCompositeOperation = 'lighter'
    bursts = bursts.filter((b) => b.alpha > 0.01)
    bursts.forEach((b) => {
      const [r, g, bb] = PALETTE[b.hue]
      ctx.beginPath()
      ctx.arc(b.x, b.y, b.r, 0, Math.PI * 2)
      ctx.strokeStyle = `rgba(${r}, ${g}, ${bb}, ${b.alpha})`
      ctx.lineWidth = 1.4
      ctx.stroke()
      b.r += 1.6
      b.alpha *= 0.94
    })
    ctx.restore()
  }

  const step = () => {
    tick++
    packets.forEach((p) => {
      p.t += p.speed
      if (p.t >= 1) {
        const c = curves[p.curve]
        if (c && bursts.length < 10) {
          bursts.push({ x: c.p3[0], y: c.p3[1], r: 3, alpha: 0.5, hue: c.hue })
        }
        p.curve = Math.floor(Math.random() * curves.length)
        p.t = 0
        p.trail = []
      }
    })
  }

  let raf = 0
  const loop = () => {
    ctx.clearRect(0, 0, w, h)
    drawFloor()
    drawCore()
    drawCurves()
    step()
    drawPackets()
    drawBursts()
    raf = requestAnimationFrame(loop)
  }

  const paintStatic = () => {
    ctx.clearRect(0, 0, w, h)
    drawFloor()
    drawCore()
    drawCurves()
    drawPackets()
  }

  resize()
  seed()
  if (reduced) {
    paintStatic()
  } else {
    raf = requestAnimationFrame(loop)
  }

  const onResize = () => {
    resize()
    seed()
    if (reduced) paintStatic()
  }
  window.addEventListener('resize', onResize)

  cleanup = () => {
    cancelAnimationFrame(raf)
    window.removeEventListener('resize', onResize)
  }
}

onMounted(init)

watch(
  () => [props.curveCount, props.packetsPerCurve],
  () => {
    cleanup?.()
    init()
  },
)

onUnmounted(() => cleanup?.())
</script>

<template>
  <canvas
    ref="canvasRef"
    class="gateway-flow-canvas"
    :style="{ opacity: props.opacity }"
    aria-hidden="true"
  />
</template>
