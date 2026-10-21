import { Autocomplete, Avatar, Box, CircularProgress, InputAdornment, TextField, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { FiSearch } from 'react-icons/fi'
import { searchMembers } from './api'
import { displayName, initials, memberNo, type Id } from './clients'
import type { View } from './Dashboard'

interface Destination { label: string; hint: string; view?: View; keywords: string; group: 'Screens' | 'Modules' }
type Option =
  | (Destination & { kind: 'destination' })
  | { kind: 'client'; group: 'Clients'; label: string; hint: string; id: Id; initials: string }

// Everything the app offers, including modules that are not built yet (listed but disabled).
const DESTINATIONS: Destination[] = [
  { label: 'Home', hint: 'Dashboard', group: 'Screens', view: 'home', keywords: 'dashboard start main tiles' },
  { label: 'Client Profile', hint: 'Find and view clients', group: 'Screens', view: 'members', keywords: 'members clients customers people profile search' },
  { label: 'Front Office', hint: 'Teller transactions', group: 'Screens', view: 'frontoffice', keywords: 'teller cash receipt disburse withdrawal deposits encashment' },
  { label: 'Chart of Accounts', hint: 'Back Office', group: 'Screens', view: 'accounts', keywords: 'back office accounts coa ledger gl' },
  { label: 'Accounting', hint: 'Trial balance, statements, journals', group: 'Screens', view: 'accounting', keywords: 'reports accounting debit credit balance statement' },
  { label: 'Approvals', hint: 'Changes waiting for approval', group: 'Screens', view: 'approvals', keywords: 'approve pending changes review requests membership officer' },
  { label: 'Users', hint: 'Passwords and roles', group: 'Screens', view: 'users', keywords: 'accounts people passwords sign in roles membership officer administrators' },
  { label: 'Loan Application', hint: 'Find a member and start a loan application', group: 'Screens', view: 'loanapp', keywords: 'loan apply new application borrow' },
  { label: 'Payables', hint: 'Coming soon', group: 'Modules', keywords: 'bills suppliers' },
  { label: 'Inventory', hint: 'Coming soon', group: 'Modules', keywords: 'stock items' },
  { label: 'POS', hint: 'Coming soon', group: 'Modules', keywords: 'point of sale cashier' },
  { label: 'Settings', hint: 'Coming soon', group: 'Modules', keywords: 'preferences configuration' },
  { label: 'System', hint: 'Coming soon', group: 'Modules', keywords: 'users security audit' },
  { label: 'Backup', hint: 'Coming soon', group: 'Modules', keywords: 'restore export' },
  { label: 'Others', hint: 'Coming soon', group: 'Modules', keywords: 'misc' },
]

const isMac = /Mac|iPhone|iPad/.test(navigator.platform)
export const GLOBAL_SEARCH_SHORTCUT = `${isMac ? '⌘' : 'Ctrl+'}K`

const MIN_CLIENT_CHARS = 2
const MAX_CLIENTS = 6

const matches = (d: Destination, q: string) => {
  const hay = `${d.label} ${d.hint} ${d.keywords}`.toLowerCase()
  return q.toLowerCase().split(/\s+/).filter(Boolean).every((w) => hay.includes(w))
}

/** Searches the whole app: screens, modules and clients. Choosing a result jumps straight to it. */
export default function GlobalSearch({ onNavigate, onOpenClient }: { onNavigate: (v: View) => void; onOpenClient: (id: Id) => void }) {
  const [input, setInput] = useState('')
  const [q, setQ] = useState('')
  const ref = useRef<HTMLInputElement>(null)

  // Debounce typing so each keystroke doesn't hit the large member table.
  useEffect(() => {
    const t = setTimeout(() => setQ(input.trim()), 250)
    return () => clearTimeout(t)
  }, [input])

  // Ctrl/Cmd+K focuses the search.
  useEffect(() => {
    const focus = () => { ref.current?.focus(); ref.current?.select() }
    const on = (e: KeyboardEvent) => {
      if ((isMac ? e.metaKey : e.ctrlKey) && e.key === 'k') { e.preventDefault(); focus() }
    }
    window.addEventListener('keydown', on)
    return () => window.removeEventListener('keydown', on)
  }, [])

  const wantClients = q.length >= MIN_CLIENT_CHARS
  const { data, isFetching } = useQuery({
    queryKey: ['members', q, 'global'],
    queryFn: () => searchMembers(q, 0, MAX_CLIENTS),
    enabled: wantClients,
    placeholderData: (prev) => prev,
  })

  const destinations: Option[] = DESTINATIONS.filter((d) => matches(d, input)).map((d) => ({ ...d, kind: 'destination' as const }))
  const clients: Option[] = wantClients
    ? (data?.content ?? []).map((m) => ({
        kind: 'client' as const, group: 'Clients' as const, label: displayName(m),
        hint: `${memberNo(m.id)}${m.city ? ` · ${m.city}` : ''}`, id: m.id, initials: initials(m),
      }))
    : []
  const options = [...destinations, ...clients]

  return (
    <Autocomplete<Option, false, false, false>
      fullWidth
      options={options}
      groupBy={(o) => o.group}
      filterOptions={(o) => o}
      getOptionLabel={(o) => o.label}
      getOptionKey={(o) => (o.kind === 'client' ? `client-${o.id.branchCode}-${o.id.clientId}` : `dest-${o.label}`)}
      getOptionDisabled={(o) => o.kind === 'destination' && !o.view}
      value={null}
      inputValue={input}
      onInputChange={(_, v, reason) => { if (reason !== 'reset') setInput(v) }}
      onChange={(_, o) => {
        if (!o) return
        if (o.kind === 'client') onOpenClient(o.id)
        else if (o.view) onNavigate(o.view)
        setInput('')
        setQ('')
        ref.current?.blur()
      }}
      openOnFocus
      autoHighlight
      blurOnSelect
      noOptionsText="Nothing matches"
      renderInput={(params) => (
        <TextField
          {...params} inputRef={ref} placeholder={`Search screens, modules and clients  (${GLOBAL_SEARCH_SHORTCUT})`}
          sx={{ '& .MuiInputBase-root': { color: 'inherit' }, '& input::placeholder': { opacity: 0.75 }, '& .MuiOutlinedInput-root': { borderRadius: '5px', fontSize: 12.5, fontWeight: 400, height: 26, py: '0 !important', bgcolor: 'transparent' }, '& .MuiOutlinedInput-notchedOutline': { borderColor: 'divider' }, '& .MuiOutlinedInput-root:hover .MuiOutlinedInput-notchedOutline': { borderColor: 'text.disabled' } }}
          slotProps={{
            ...params.slotProps,
            htmlInput: { ...params.slotProps.htmlInput, 'aria-label': 'Search the whole app' },
            input: {
              ...params.slotProps.input,
              startAdornment: <InputAdornment position="start"><FiSearch /></InputAdornment>,
              endAdornment: <>{isFetching && wantClients ? <CircularProgress size={14} /> : null}{params.slotProps.input.endAdornment}</>,
            },
          }}
        />
      )}
      renderOption={(props, o) => {
        const { key, ...rest } = props as typeof props & { key: string }
        return (
          <Box component="li" key={key} {...rest} sx={{ display: 'flex', alignItems: 'center', gap: 1.25, borderRadius: '5px', mx: 0.5 }}>
            {o.kind === 'client' && (
              <Avatar sx={{ width: 28, height: 28, fontSize: 11, fontWeight: 600, bgcolor: 'action.hover', color: 'text.secondary' }}>{o.initials}</Avatar>
            )}
            <Typography noWrap sx={{ fontSize: 14, fontWeight: 500, flex: 1, minWidth: 0 }}>{o.label}</Typography>
            <Typography noWrap sx={{ fontSize: 12, fontVariantNumeric: 'tabular-nums' }} color="text.secondary">{o.hint}</Typography>
          </Box>
        )
      }}
      slotProps={{
        paper: { sx: { borderRadius: '5px', mt: 0.5, boxShadow: '0 12px 32px rgba(0,0,0,0.18)', border: 1, borderColor: 'divider' } },
        popper: { style: { minWidth: 400 }, placement: 'bottom' },
        listbox: { sx: { py: 0.5 } },
      }}
    />
  )
}
