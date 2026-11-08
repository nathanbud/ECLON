import type { Theme } from '@mui/material'
import { RADIUS } from './theme'

/**
 * The light theme's whites. Pure white (#FFFFFF) next to a dark-on-light interface glares, so surfaces are soft off-whites on a cool grey canvas, the colour washes are muted
 * and a thin dark hairline defines the edges instead of a bright rim. Text stays #1D1D1F on these (about 16:1).
 */
export const LIGHT = {
  canvas: '#F3F4F6',          // the page
  paper: '#FAFAFB',           // dialogs, menus, tables
  field: 'rgba(250,250,252,0.7)', // text fields on a glass surface
  panel: 'rgba(250,250,252,0.62)', // a panel inside a glass frame
  bar: 'rgba(246,247,249,0.8)',    // the title bar
  hairline: 'rgba(0,0,0,0.07)',
  washBlue: '#DCE6F2',
  washLilac: '#E5E1F0',
} as const

/**
 * The dark side panel of the member profile (a charcoal column with a light-on-dark menu), the same in both themes so it reads as the profile's anchor. `selected` is the open
 * section's row: a light bar with dark text. Text and line colours pass WCAG AA on `bg`.
 */
export const SIDE = {
  bg: '#3A3D42',
  bgDark: '#1F2125',
  text: '#F2F3F5',
  muted: 'rgba(242,243,245,0.72)',
  line: 'rgba(255,255,255,0.14)',
  hover: 'rgba(255,255,255,0.08)',
  selected: '#F3F4F6',
  selectedText: '#1D1D1F',
  // status colours for tags on the panel (light tints of the usual green, amber and red, readable on the charcoal)
  ok: '#8FE0A6',
  warn: '#FFCB7A',
  bad: '#FFA3A3',
} as const

/** The soft colour wash behind glass surfaces, so the glass has something to refract. */
export const appBackground = (t: Theme) =>
  t.palette.mode === 'dark'
    ? 'radial-gradient(1200px 600px at 15% 0%, #1b3a6b 0%, transparent 60%), radial-gradient(900px 600px at 100% 100%, #3b1f5e 0%, transparent 55%), #0b0b0f'
    : `radial-gradient(1200px 600px at 15% 0%, ${LIGHT.washBlue} 0%, transparent 60%), radial-gradient(900px 600px at 100% 100%, ${LIGHT.washLilac} 0%, transparent 55%), ${LIGHT.canvas}`

/**
 * Frosted-glass surface: translucent fill, background blur, a lit rim and layered shadows for depth.
 * `elevated` adds the deep shadow used for the main card; the default suits smaller cards.
 */
export function glass(t: Theme, { elevated = false }: { elevated?: boolean } = {}) {
  const dark = t.palette.mode === 'dark'
  return {
    background: dark
      ? 'linear-gradient(145deg, rgba(58,58,64,0.62), rgba(28,28,32,0.5))'
      : 'linear-gradient(145deg, rgba(251,251,252,0.8), rgba(247,248,250,0.6))',
    backdropFilter: 'blur(24px) saturate(180%)',
    border: `1px solid ${dark ? 'rgba(255,255,255,0.16)' : LIGHT.hairline}`,
    borderRadius: `${RADIUS}px`,
    boxShadow: dark
      ? `inset 0 1px 0 rgba(255,255,255,0.12), 0 1px 2px rgba(0,0,0,0.3)${elevated ? ', 0 8px 24px rgba(0,0,0,0.3)' : ', 0 2px 8px rgba(0,0,0,0.18)'}`
      : `inset 0 1px 0 rgba(255,255,255,0.55), 0 1px 2px rgba(20,40,90,0.06)${elevated ? ', 0 8px 24px rgba(20,40,90,0.10)' : ', 0 2px 8px rgba(20,40,90,0.05)'}`,
  } as const
}
