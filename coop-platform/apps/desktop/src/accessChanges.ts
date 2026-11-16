/**
 * What changed in a person's access between two readings of /api/me/access, in words for the notice ("You can now: Add loan application").
 * Pure, so it can be tested.
 */
export interface AccessItem { key: string; group: string; label: string; value: string }

export interface AccessChange { text: string; where: string }
export interface AccessChanges { allowed: AccessChange[]; removed: AccessChange[]; changed: AccessChange[] }

const TRANS = 'Transaction types · '

/** How one item is named in the notice: a role as "Accountant role", a transaction right as "Cash Receipt: Post", any other by its label. */
function name(i: AccessItem): AccessChange {
  if (i.key.startsWith('role:')) return { text: `${i.label} role`, where: 'Roles' }
  if (i.group.startsWith(TRANS)) return { text: `${i.group.slice(TRANS.length)}: ${i.label}`, where: 'Transaction types' }
  return { text: i.label, where: i.group }
}

const yes = (i: AccessItem | undefined) => i?.value === 'Yes'
const isSwitch = (i: AccessItem) => i.value === 'Yes' || i.value === 'No'

export function accessChanges(before: AccessItem[], after: AccessItem[]): AccessChanges {
  const was = new Map(before.map((i) => [i.key, i]))
  const now = new Map(after.map((i) => [i.key, i]))
  const out: AccessChanges = { allowed: [], removed: [], changed: [] }
  for (const n of after) {
    const w = was.get(n.key)
    if (w?.value === n.value) continue
    if (isSwitch(n) && (!w || isSwitch(w))) {
      if (yes(n) && !yes(w)) out.allowed.push(name(n))
      else if (!yes(n) && yes(w)) out.removed.push(name(n))
    } else if (w) {
      out.changed.push({ text: `${name(n).text}: ${w.value} → ${n.value}`, where: n.group })
    }
  }
  for (const w of before) if (!now.has(w.key) && yes(w)) out.removed.push(name(w))
  return out
}

export const anyChange = (c: AccessChanges) => c.allowed.length + c.removed.length + c.changed.length > 0
