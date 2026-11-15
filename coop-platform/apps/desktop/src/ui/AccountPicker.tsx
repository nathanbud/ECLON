import { Autocomplete, Box, TextField, Typography, type SxProps, type Theme } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'

export type PickedAccount = { code: string; description: string }

const readRecent = (key: string): PickedAccount[] => {
  try { return JSON.parse(localStorage.getItem(key) ?? '[]') } catch { return [] }
}
const remember = (key: string, a: PickedAccount) => {
  try { localStorage.setItem(key, JSON.stringify([a, ...readRecent(key).filter((x) => x.code !== a.code)].slice(0, 8))) } catch { /* storage unavailable */ }
}

/**
 * Finds a ledger account by number or name as you type, instead of asking for the number. With nothing typed it offers the accounts used
 * last on this computer (kept per {@code recentKey}). {@code search} decides which accounts may be chosen.
 */
export function AccountPicker({ code, desc, onPick, label, search, recentKey, autoFocus, placeholder = 'Account', width = 150, sx }: {
  code: string
  desc: string
  onPick: (a: PickedAccount | null) => void
  label: string
  search: (q: string) => Promise<PickedAccount[]>
  recentKey: string
  autoFocus?: boolean
  placeholder?: string
  width?: number
  sx?: SxProps<Theme>
}) {
  const [input, setInput] = useState('')
  const [q, setQ] = useState('')
  useEffect(() => {
    const t = setTimeout(() => setQ(input.trim()), 200)
    return () => clearTimeout(t)
  }, [input])
  const { data } = useQuery({ queryKey: ['account-search', recentKey, q], queryFn: () => search(q), enabled: q.length >= 1, staleTime: 60_000 })
  const value = code ? { code, description: desc } : null
  const options = q.length >= 1 ? data ?? [] : readRecent(recentKey)
  return (
    <Autocomplete
      size="small" options={options} value={value} filterOptions={(o) => o} autoHighlight openOnFocus
      getOptionLabel={(o) => o.code} isOptionEqualToValue={(a, b) => a.code === b.code}
      groupBy={() => (q.length >= 1 ? 'Accounts' : 'Recently used')}
      onChange={(_, v) => { if (v) remember(recentKey, v); onPick(v) }} onInputChange={(_, v, reason) => { if (reason === 'input') setInput(v) }}
      noOptionsText="Type an account number or part of its name"
      renderOption={(props, o) => {
        const { key, ...rest } = props as typeof props & { key: string }
        return (
          <Box component="li" key={key} {...rest} sx={{ display: 'flex', gap: 1.5 }}>
            <Typography sx={{ fontSize: 13, fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>{o.code}</Typography>
            <Typography noWrap sx={{ fontSize: 13 }} color="text.secondary">{o.description}</Typography>
          </Box>
        )
      }}
      renderInput={(params) => <TextField {...params} autoFocus={autoFocus} placeholder={placeholder} slotProps={{ ...params.slotProps, htmlInput: { ...params.slotProps.htmlInput, 'aria-label': label } }} sx={sx} />}
      sx={{ width }} slotProps={{ popper: { style: { minWidth: 320 } } }}
    />
  )
}
