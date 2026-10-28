import { type DetailField } from '../api'

// ---- formatting helpers -------------------------------------------------------------------------------------------

const parseDate = (iso: string) => new Date(`${iso.slice(0, 10)}T00:00:00`)
export const fmtDate = (iso?: string | null) =>
  iso ? parseDate(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }) : null
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

export const peso = (n: number | null | undefined) => (n == null ? '—' : n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }))
