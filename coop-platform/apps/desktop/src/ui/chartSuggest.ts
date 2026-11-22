import type { ChartAccount } from '../api'

/**
 * Where to put a new chart account: the headings it most likely belongs under, best first, each with the reason and the next free number under it. Only a suggestion; the person
 * still chooses and the server checks as before. A heading scores when the new name shares words with the heading's own name (counted double) or with the accounts already
 * under it; when a number is typed, only headings whose number starts it are considered (the chart's rule), the deepest first. Pure.
 */
export interface HeadingSuggestion { heading: ChartAccount; why: string; nextCode: string }

const STOP = new Set(['the', 'and', 'for', 'of', 'on', 'in', 'to', 'account', 'accounts', 'other', 'others', 'new', 'from', 'with'])

export function words(text: string | null | undefined): string[] {
  const out: string[] = []
  for (const w of (text ?? '').toLowerCase().split(/[^a-z]+/)) if (w.length >= 3 && !STOP.has(w) && !out.includes(w)) out.push(w)
  return out
}

/** One word matches another when one starts the other: fund matches funds. */
const match = (w: string, inn: string[]) => inn.some((x) => x.startsWith(w) || w.startsWith(x))

/**
 * The next number under a heading: one more than the highest number of the same length already under it, or the heading's number followed by 01 when it has none (the
 * old chart's own steps, as 20101 → 2010101). Never a number already in the chart; null when the steps run out.
 */
export function nextCode(chart: ChartAccount[], heading: ChartAccount): string | null {
  const taken = new Set(chart.map((a) => a.code))
  const kids = chart.filter((a) => a.parent === heading.code && a.code.startsWith(heading.code) && /^\d+$/.test(a.code))
  const width = kids.length > 0 ? mostCommon(kids.map((k) => k.code.length - heading.code.length)) : 2
  if (heading.code.length + width > 10) return null
  const same = kids.filter((k) => k.code.length === heading.code.length + width).map((k) => Number(k.code.slice(heading.code.length)))
  let n = same.length > 0 ? Math.max(...same) + 1 : 1
  for (; String(n).length <= width; n++) {
    const c = heading.code + String(n).padStart(width, '0')
    if (!taken.has(c)) return c
  }
  // the steps of that width are used up (…7, 8, 9): carry on with one digit more (10, 11, …), as long as the number stays within 10 digits
  if (heading.code.length + width + 1 > 10) return null
  for (; String(n).length <= width + 1; n++) {
    const c = heading.code + String(n).padStart(width + 1, '0')
    if (!taken.has(c)) return c
  }
  return null
}

function mostCommon(xs: number[]): number {
  const count = new Map<number, number>()
  for (const x of xs) count.set(x, (count.get(x) ?? 0) + 1)
  return [...count.entries()].sort((a, b) => b[1] - a[1] || a[0] - b[0])[0][0]
}

export function suggestHeadings(chart: ChartAccount[], name: string, code: string, max = 3): HeadingSuggestion[] {
  const headings = chart.filter((a) => a.summary && !a.disabled)
  const out: HeadingSuggestion[] = []
  const add = (h: ChartAccount, why: string) => {
    if (out.some((o) => o.heading.code === h.code)) return
    const next = nextCode(chart, h)
    if (next) out.push({ heading: h, why, nextCode: next })
  }
  const typed = code.trim()
  const candidates = typed ? headings.filter((h) => typed.startsWith(h.code)) : headings
  const mine = words(name)
  if (mine.length > 0) {
    const kidsOf = new Map<string, ChartAccount[]>()
    for (const a of chart) if (a.parent) kidsOf.set(a.parent, [...(kidsOf.get(a.parent) ?? []), a])
    const scored = candidates.flatMap((h) => {
      const own = words(h.name)
      let score = 0
      for (const w of mine) if (match(w, own)) score += 2
      const alike = (kidsOf.get(h.code) ?? []).filter((k) => mine.some((w) => match(w, words(k.name))))
      score += Math.min(alike.length, 3)
      if (score === 0) return []
      const why = alike.length > 0
        ? `Similar accounts are under it: ${alike.slice(0, 2).map((k) => k.name ?? k.code).join(', ')}${alike.length > 2 ? ` and ${alike.length - 2} more` : ''}`
        : 'Its name matches the new account’s name'
      return [{ h, score, why }]
    })
    scored.sort((a, b) => b.score - a.score || b.h.code.length - a.h.code.length || a.h.code.localeCompare(b.h.code))
    for (const s of scored) { if (out.length >= max) break; add(s.h, s.why) }
  }
  // a number typed: the deepest heading it starts with, the chart's own rule
  if (typed && out.length < max) {
    const deepest = [...candidates].sort((a, b) => b.code.length - a.code.length)[0]
    if (deepest) add(deepest, `The number starts with ${deepest.code}`)
  }
  return out.slice(0, max)
}
