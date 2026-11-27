import { describe, expect, it } from 'vitest'
import { useTheme } from '@mui/material'
import { render } from '@testing-library/react'
import { createElement } from 'react'
import { AppThemeProvider, CONTROL_H, INPUT_PAD, RADIUS, SURFACE_RADIUS, TYPE, tileFill } from './theme'
import { LIGHT } from './glass'

// DESIGN.md (beside src) describes the look in words and tokens; these checks fail when its tokens and the theme drift apart.
const files = import.meta.glob('../DESIGN.md', { query: '?raw', import: 'default', eager: true })
const doc = String(Object.values(files)[0] ?? '')
const tokens = doc.split(/^---$/m)[1] ?? ''

describe('DESIGN.md', () => {
  it('is there, with its tokens', () => {
    expect(tokens).toContain('colors:')
    expect(tokens).toContain('typography:')
  })

  it('uses exactly the type scale of the theme', () => {
    const sizes = new Set([...tokens.matchAll(/fontSize: (\d+)/g)].map(m => Number(m[1])))
    expect([...sizes].sort((a, b) => a - b)).toEqual(Object.values(TYPE).sort((a, b) => a - b))
  })

  it('has the theme’s sizes, corners and padding', () => {
    expect(tokens).toMatch(new RegExp(`controlHeight: ${CONTROL_H}\\b`))
    expect(tokens).toMatch(new RegExp(`control: ${RADIUS}\\b`))
    expect(tokens).toMatch(new RegExp(`surface: ${SURFACE_RADIUS}\\b`))
    expect(tokens).toMatch(new RegExp(`input-pad-y: ${INPUT_PAD.y}\\b`))
    expect(tokens).toMatch(new RegExp(`input-pad-x: ${INPUT_PAD.x}\\b`))
  })

  it('has the theme’s colours', () => {
    expect(tokens).toContain('primary: "#0A6CDC"')
    for (const [name, value] of Object.entries({ canvas: LIGHT.canvas, paper: LIGHT.paper, hairline: LIGHT.hairline, 'wash-blue': LIGHT.washBlue, 'wash-lilac': LIGHT.washLilac }))
      expect(tokens).toContain(`${name}: "${value}"`)
    for (const [name, value] of Object.entries(tileFill)) expect(tokens).toContain(`tile-${name}: "${value}"`)
  })

  it('has the theme’s warning amber, which stays readable as text and under white text (WCAG AA 4.5:1)', () => {
    let warning = ''
    function Read() { warning = useTheme().palette.warning.main; return null }
    localStorage.setItem('coop.themeMode', 'light')
    render(createElement(AppThemeProvider, null, createElement(Read)))
    expect(tokens).toContain(`warning: "${warning}"`)
    expect(contrast(warning, LIGHT.paper)).toBeGreaterThanOrEqual(4.5)
    expect(contrast(warning, LIGHT.canvas)).toBeGreaterThanOrEqual(4.5)
    expect(contrast(warning, '#FFFFFF')).toBeGreaterThanOrEqual(4.5)
  })
})

/** WCAG contrast ratio of two #RRGGBB colours. */
function contrast(a: string, b: string): number {
  const lum = (hex: string) => {
    const [r, g, bl] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4))
    return 0.2126 * r + 0.7152 * g + 0.0722 * bl
  }
  const [x, y] = [lum(a), lum(b)].sort((m, n) => n - m)
  return (x + 0.05) / (y + 0.05)
}
