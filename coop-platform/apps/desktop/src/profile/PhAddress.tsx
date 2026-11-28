import { Autocomplete, Box, TextField, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useMemo, type ReactNode } from 'react'
import { streetSuggestions } from '../api'
import { INPUT_PAD } from '../theme'
import { fieldSx, useEdit } from './editing'

/**
 * Province first, then the city or municipality in it, then the barangay in that: each choice narrows the next list, and the next field stays shut until the one before
 * it is chosen. The list is the PSA's Philippine Standard Geographic Code, one static file made by `scripts/gen-ph-addresses.py` and read only when an address is being edited.
 * What is saved is the official name in capitals, as the cooperative's records are written. A value already on file that is not in the list stays shown (and kept)
 * until the person picks another from the list.
 */
interface Data { p: string[]; c: [string, number][]; b: [string, number][] }
interface Opt { key: string; name: string; sub?: string; idx: number }

const load = async (): Promise<Data> => {
  const res = await fetch(`${import.meta.env.BASE_URL}ph-addresses/all.json`)
  if (!res.ok) throw new Error(`Address list unavailable (${res.status})`)
  return res.json()
}
const key = (s: string) => s.trim().toUpperCase()
const same = (a: string, b: string) => key(a) === key(b)
const MAX_LISTED = 50

/** Provinces shown at the top of the list, before the rest in their usual order: the cooperative's own province, where most members live. */
export const FIRST_PROVINCES = ['BENGUET']

/**
 * A search box styled like the plain address fields beside it. A drop-down box brings its own, tighter padding, which made it a few pixels shorter than its neighbours; this
 * puts back the app's input padding (`INPUT_PAD`), keeping room on the right for the arrow and the clear button.
 */
const boxSx = {
  ...fieldSx(true),
  '&& .MuiOutlinedInput-root': { padding: `${INPUT_PAD.y}px ${INPUT_PAD.x}px`, paddingRight: '58px', '& .MuiAutocomplete-input': { padding: 0, minWidth: 30 } },
  '&& .MuiAutocomplete-endAdornment': { right: 6 },
} as const

/** The saved text of a chosen name. */
const saved = (name: string) => key(name)

export interface Cols { province: string; city: string; barangay: string }
export interface Stored { province?: string | null; city?: string | null; barangay?: string | null }

export function PhAddressFields({ cols, stored, children }: { cols: Cols; stored: Stored; children: (parts: { province: ReactNode; city: ReactNode; barangay: ReactNode }) => ReactNode }) {
  const { draft, set } = useEdit()
  const orig = { province: stored.province ?? '', city: stored.city ?? '', barangay: stored.barangay ?? '' }
  const now = {
    province: draft[cols.province] ?? orig.province,
    city: draft[cols.city] ?? orig.city,
    barangay: draft[cols.barangay] ?? orig.barangay,
  }
  const list = useQuery({ queryKey: ['ph-addresses'], queryFn: load, staleTime: Infinity, retry: false })
  const d = list.data

  const all = useMemo(() => {
    if (!d) return null
    const listed: Opt[] = d.p.map((n, i) => ({ key: `p${i}`, name: n, idx: i }))
    const first = (o: Opt) => FIRST_PROVINCES.findIndex((f) => same(f, o.name))
    const provinces = [...FIRST_PROVINCES.flatMap((f) => listed.filter((o) => same(f, o.name))), ...listed.filter((o) => first(o) < 0)]
    const cities: (Opt & { prov: number })[] = d.c.map(([n, p], i) => ({ key: `c${i}`, name: n, idx: i, prov: p }))
    const barangays: (Opt & { city: number })[] = d.b.map(([n, c], i) => ({ key: `b${i}`, name: n, idx: i, city: c }))
    return { provinces, cities, barangays }
  }, [d])

  // what is on file or chosen, found in the list: the province by name, then the city within it, then the barangay within that
  const prov = all?.provinces.find((p) => same(p.name, now.province)) ?? null
  const cityOpt = all?.cities.find((c) => same(c.name, now.city) && (!prov || c.prov === prov.idx)) ?? null
  const brgyOpt = all && cityOpt ? all.barangays.find((b) => b.city === cityOpt.idx && same(b.name, now.barangay)) ?? null : null

  const onFile = (current: string, found: Opt | null): Opt[] => (current && !found ? [{ key: 'file', name: current, idx: -1 }] : [])
  const provinceOptions = [...onFile(now.province, prov), ...(all?.provinces ?? [])]
  const cityOptions = [...onFile(now.city, cityOpt), ...(prov ? all?.cities.filter((c) => c.prov === prov.idx) ?? [] : [])]
  const barangayOptions = [...onFile(now.barangay, brgyOpt), ...(all && cityOpt ? all.barangays.filter((b) => b.city === cityOpt.idx) : [])]

  const put = (col: 'province' | 'city' | 'barangay', value: string) => set(cols[col], value ? saved(value) : '', orig[col])

  const pickProvince = (o: Opt | null) => {
    put('province', o?.name ?? '')
    if (!o || !cityOpt || cityOpt.prov !== o.idx) { put('city', ''); put('barangay', '') }
  }
  const pickCity = (o: Opt | null) => {
    put('city', o?.name ?? '')
    if (!o || !brgyOpt || brgyOpt.city !== o.idx) put('barangay', '')
  }
  const pickBarangay = (o: Opt | null) => put('barangay', o?.name ?? '')

  // a typed search: names that start with the letters first, then those that contain them; a long list needs a couple of letters before it shows anything
  const search = () => (options: Opt[], { inputValue }: { inputValue: string }) => {
    const q = inputValue.trim().toLowerCase()
    if (!q) return options.slice(0, MAX_LISTED * 4)
    const starts: Opt[] = []
    const holds: Opt[] = []
    for (const o of options) {
      const n = o.name.toLowerCase()
      if (n.startsWith(q)) starts.push(o)
      else if (n.includes(q)) holds.push(o)
      if (starts.length >= MAX_LISTED) break
    }
    return [...starts, ...holds].slice(0, MAX_LISTED)
  }

  const unknown = (current: string, found: Opt | null) => (current && !found && all ? 'Not in the official list. Choose from the list to replace it.' : undefined)

  const box = (label: string, current: string, found: Opt | null, options: Opt[], onPick: (o: Opt | null) => void, disabled: boolean, empty: string, help?: string) => {
    const value = found ?? (current ? options.find((o) => o.key === 'file') ?? null : null)
    return (
      <Autocomplete
        size="small" fullWidth autoHighlight openOnFocus
        options={options} value={value} filterOptions={search()}
        isOptionEqualToValue={(a, b) => a.key === b.key} getOptionLabel={(o) => o.name}
        onChange={(_, o) => onPick(o)}
        disabled={disabled} loading={list.isLoading} loadingText="Loading the list…" noOptionsText={empty}
        renderOption={(props, o) => {
          const { key: k, ...rest } = props as typeof props & { key: string }
          return (
            <li key={k} {...rest}>
              <Box sx={{ minWidth: 0 }}>
                <Typography noWrap>{o.name}</Typography>
                {o.sub && <Typography variant="body2" color="text.secondary" noWrap>{o.sub}</Typography>}
              </Box>
            </li>
          )
        }}
        renderInput={(params) => (
          <TextField
            {...params} label={label} placeholder="Type to search" helperText={help}
            slotProps={{ ...params.slotProps, inputLabel: { ...params.slotProps?.inputLabel, shrink: true } }}
            sx={boxSx}
          />
        )}
      />
    )
  }

  // a value on file that is not in the list keeps its field open, so it can be seen and replaced
  return <>{children({
    province: box('Province', now.province, prov, provinceOptions, pickProvince, false, 'Nothing matches', unknown(now.province, prov)),
    city: box('City or Municipality', now.city, cityOpt, cityOptions, pickCity, !prov, 'Nothing matches', unknown(now.city, cityOpt) ?? (!prov && now.city ? 'Choose the province first.' : undefined)),
    barangay: box('Barangay', now.barangay, brgyOpt, barangayOptions, pickBarangay, !cityOpt, 'Nothing matches', unknown(now.barangay, brgyOpt) ?? (!cityOpt && now.barangay ? 'Choose the city or municipality first.' : undefined)),
  })}</>
}

/**
 * The street box while an address is edited: type anything, or pick a street already used in the chosen barangay (there is no official list of streets, so the member
 * records are the source). Until a barangay is chosen it is an ordinary box.
 */
export function StreetField({ label, col, stored, place }: { label: string; col: string; stored?: string | null; place: { cols: Cols; stored: Stored } }) {
  const { draft, set } = useEdit()
  const orig = stored ?? ''
  const text = draft[col] ?? orig
  const at = (c: keyof Cols) => draft[place.cols[c]] ?? place.stored[c] ?? ''
  const barangay = at('barangay')
  const city = at('city')
  const province = at('province')
  const { data } = useQuery({
    queryKey: ['street-suggestions', key(barangay), key(city), key(province)],
    queryFn: () => streetSuggestions(barangay, city, province),
    enabled: !!barangay.trim(), staleTime: 5 * 60_000, retry: false,
  })
  return (
    <Autocomplete
      freeSolo size="small" fullWidth autoHighlight options={data ?? []} inputValue={text}
      onInputChange={(_, v, reason) => { if (reason !== 'reset') set(col, v, orig) }}
      onChange={(_, v) => { if (typeof v === 'string') set(col, v, orig) }}
      renderInput={(params) => (
        <TextField
          {...params} label={label} placeholder={data && data.length ? 'Type or choose a street in this barangay' : undefined}
          slotProps={{ ...params.slotProps, inputLabel: { ...params.slotProps?.inputLabel, shrink: true } }}
          sx={boxSx}
        />
      )}
    />
  )
}
