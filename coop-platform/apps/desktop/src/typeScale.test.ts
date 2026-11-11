import { describe, expect, it } from 'vitest'
import { TYPE } from './theme'

/** Printed slips are laid out in points for paper, and the theme is where the scale itself lives. */
const SKIP = /(Receipt|ServiceInvoice|theme)\.tsx$|\.test\./

const sources = Object.entries(import.meta.glob<string>('./**/*.{ts,tsx}', { query: '?raw', import: 'default', eager: true }))
  .filter(([path]) => !SKIP.test(path))

function offScale(pattern: RegExp, allowed: Set<number>, what: string): string[] {
  const off: string[] = []
  for (const [path, text] of sources) {
    text.split('\n').forEach((line, i) => {
      for (const m of line.matchAll(pattern)) if (!allowed.has(Number(m[1]))) off.push(`${path}:${i + 1} ${what} ${m[1]}`)
    })
  }
  return off
}

/** Every font size and weight a screen sets must come from the one scale in the theme, so the same kind of text is the same size everywhere. */
describe('type scale', () => {
  it('reads the screens', () => {
    expect(sources.length).toBeGreaterThan(100)
  })

  it('uses only the sizes in TYPE', () => {
    const allowed = new Set<number>(Object.values(TYPE))
    // A plain number only: sizes computed from a scale factor (the sign-in brand block) are left alone.
    const off = offScale(/fontSize: (\d+(?:\.\d+)?)(?![\d.])(?!\s*\*)/g, allowed, 'fontSize')
    expect(off, `Use a size from TYPE in theme.tsx (${[...allowed].join(', ')})`).toEqual([])
  })

  it('uses only regular, medium, semibold and bold weights (and light for large figures)', () => {
    expect(offScale(/fontWeight: (\d+)\b/g, new Set([300, 400, 500, 600, 700]), 'fontWeight')).toEqual([])
  })
})
