import { describe, expect, it } from 'vitest'
import { CONTROL_H, INPUT_PAD, RADIUS, SURFACE_RADIUS, TYPE, tileFill } from './theme'
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
})
