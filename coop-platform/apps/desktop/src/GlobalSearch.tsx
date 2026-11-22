import { Autocomplete, Avatar, Box, CircularProgress, InputAdornment, TextField, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { FiSearch, FiUser } from 'react-icons/fi'
import { searchMembers, type Me } from './api'
import { displayName, memberNo, type Id } from './clients'
import type { View } from './Dashboard'
import { DOMAINS, HOME, visibleScreens } from './nav/screens'
import { useMe } from './session'

interface Destination { label: string; hint: string; view: View; tab?: string; keywords: string; group: string }
type Option =
  | (Destination & { kind: 'destination' })
  | { kind: 'client'; group: 'Members'; label: string; hint: string; id: Id }

/** The screens this person may open, from the one list of screens the side menu and the tiles also use, plus Home. */
function destinationsFor(me: Me): Destination[] {
  return [
    { label: HOME.label, hint: HOME.note, group: 'Go to', view: 'home', keywords: 'dashboard start main tiles' },
    ...visibleScreens(me).map((s) => ({ label: s.label, hint: s.note, group: DOMAINS[s.domain].label, view: s.view, tab: s.tab, keywords: s.keywords ?? '' })),
  ]
}

const isMac = /Mac|iPhone|iPad/.test(navigator.platform)
export const GLOBAL_SEARCH_SHORTCUT = `${isMac ? '⌘' : 'Ctrl+'}K`

const MIN_CLIENT_CHARS = 2
const MAX_CLIENTS = 6

const matches = (d: Destination, q: string) => {
  const hay = `${d.label} ${d.hint} ${d.keywords}`.toLowerCase()
  return q.toLowerCase().split(/\s+/).filter(Boolean).every((w) => hay.includes(w))
}

/** Searches the whole app: screens, modules and clients. Choosing a result jumps straight to it. */
export default function GlobalSearch({ onNavigate, onOpenClient }: { onNavigate: (v: View, tab?: string) => void; onOpenClient: (id: Id) => void }) {
  const me = useMe()
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

  const destinations: Option[] = destinationsFor(me).filter((d) => matches(d, input)).map((d) => ({ ...d, kind: 'destination' as const }))
  const clients: Option[] = wantClients
    ? (data?.content ?? []).map((m) => ({
        kind: 'client' as const, group: 'Members' as const, label: displayName(m),
        hint: `${memberNo(m.id)}${m.city ? ` · ${m.city}` : ''}`, id: m.id,
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
      getOptionKey={(o) => (o.kind === 'client' ? `client-${o.id.branchCode}-${o.id.clientId}` : `dest-${o.view}-${o.tab ?? ''}`)}
      value={null}
      inputValue={input}
      onInputChange={(_, v, reason) => { if (reason !== 'reset') setInput(v) }}
      onChange={(_, o) => {
        if (!o) return
        if (o.kind === 'client') onOpenClient(o.id)
        else onNavigate(o.view, o.tab)
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
          {...params} inputRef={ref} placeholder={`Search screens, modules and members  (${GLOBAL_SEARCH_SHORTCUT})`}
          sx={{ '& .MuiInputBase-root': { color: 'inherit' }, '& input::placeholder': { opacity: 0.75 }, '& .MuiOutlinedInput-root': { borderRadius: '5px', fontSize: 12, fontWeight: 400, height: 26, py: '0 !important', bgcolor: 'transparent' }, '& .MuiOutlinedInput-notchedOutline': { borderColor: 'divider' }, '& .MuiOutlinedInput-root:hover .MuiOutlinedInput-notchedOutline': { borderColor: 'text.disabled' } }}
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
              <Avatar sx={{ width: 28, height: 28, fontSize: 11, fontWeight: 600, bgcolor: 'action.hover', color: 'text.secondary' }}><FiUser size={16} aria-hidden /></Avatar>
            )}
            <Typography noWrap sx={{ fontSize: 13, fontWeight: 500, flex: 1, minWidth: 0 }}>{o.label}</Typography>
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
