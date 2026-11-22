import type { UnitChoice } from '../api'

/** A unit in the order the department tree reads (each unit followed by those under it), with how deep it sits. Pure. */
export interface UnitOption { unit: UnitChoice; depth: number }

export function unitOptions(units: UnitChoice[]): UnitOption[] {
  const ids = new Set(units.map((u) => u.id))
  const kids = new Map<number, UnitChoice[]>()
  const roots: UnitChoice[] = []
  for (const u of units) {
    if (u.parent == null || !ids.has(u.parent) || u.parent === u.id) roots.push(u)
    else kids.set(u.parent, [...(kids.get(u.parent) ?? []), u])
  }
  const out: UnitOption[] = []
  const seen = new Set<number>()
  const walk = (u: UnitChoice, depth: number) => {
    if (seen.has(u.id)) return
    seen.add(u.id)
    out.push({ unit: u, depth })
    for (const k of [...(kids.get(u.id) ?? [])].sort((a, b) => a.id - b.id)) walk(k, depth + 1)
  }
  for (const r of [...roots].sort((a, b) => a.id - b.id)) walk(r, 0)
  // a unit caught in a loop of the old data still shows, at the top
  for (const u of units) if (!seen.has(u.id)) walk(u, 0)
  return out
}

/** How a statement names its unit: "BAUKO SATELLITE OFFICE, satellite office", or for a consolidation how many units it adds up. */
export function unitHeading(u: { name: string; kindName: string; kind: string; units: number }): string {
  return `${u.name}, ${u.kindName.toLowerCase()}${u.units > 1 ? ` of ${u.units} units` : ''}`
}
