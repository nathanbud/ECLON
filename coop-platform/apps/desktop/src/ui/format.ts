/** Number formatting shared by every screen, so an amount reads the same everywhere. */

/** An amount with thousands separators and two decimals (1,234.50), in the person's own locale. */
export const money = (n: number) => n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })

/** The same, or `fallback` (a dash, or nothing) when there is no amount. */
export const moneyOr = (n: number | null | undefined, fallback: string) => (n == null ? fallback : money(n))

/** Reads what a person typed in an amount box ("1,234.50") as a number; NaN when it is not a number. */
export const parseAmount = (s: string) => Number(s.replace(/,/g, ''))

/** Style that keeps digits the same width so columns of figures line up. */
export const tabular = { fontVariantNumeric: 'tabular-nums' } as const

/** A file size for people: 6610000 -> "6.3 MB". */
export const sizeOf = (bytes: number) => {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
