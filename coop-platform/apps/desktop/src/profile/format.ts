import { type DetailField } from '../api'
import { moneyOr } from '../ui/format'

// ---- formatting helpers -------------------------------------------------------------------------------------------

const parseDate = (iso: string) => new Date(`${iso.slice(0, 10)}T00:00:00`)
/** A date as numbers, month first: 2026-01-31 reads 01/31/2026. */
export const fmtDate = (iso?: string | null) => (iso ? `${iso.slice(5, 7)}/${iso.slice(8, 10)}/${iso.slice(0, 4)}` : null)
export const yearsSince = (iso?: string | null) => {
  if (!iso) return null
  const d = parseDate(iso)
  const now = new Date()
  let y = now.getFullYear() - d.getFullYear()
  if (now.getMonth() < d.getMonth() || (now.getMonth() === d.getMonth() && now.getDate() < d.getDate())) y--
  return y
}

export function formatField(f: DetailField): string | null {
  if (f.value == null) return null
  if (f.kind === 'date') return fmtDate(f.value)
  if (f.kind === 'money') {
    const n = Number(f.value)
    return Number.isNaN(n) ? f.value : n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  }
  return f.value
}

export const when = (at: string) => new Date(at.replace(' ', 'T')).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })

export const peso = (n: number | null | undefined) => moneyOr(n, '—')
