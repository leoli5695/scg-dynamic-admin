import { defineComponent, h, type Component } from 'vue'

/**
 * Adapts a Lucide icon to the console's AntD-shaped icon contract:
 * `.anticon` wrapper, 1em sizing (font-size drives the glyph), 1.8 stroke.
 */
export function createIcon(name: string, icon: Component) {
  return defineComponent({
    name,
    inheritAttrs: false,
    setup(_, { attrs }) {
      return () =>
        h(
          'span',
          {
            ...attrs,
            'aria-hidden': 'true',
            class: ['anticon', 'app-icon', attrs.class],
          },
          [
            h(icon, {
              // Lucide types `size` as number, but its renderer accepts CSS lengths.
              size: '1em' as unknown as number,
              strokeWidth: 1.8,
            }),
          ],
        )
    },
  })
}