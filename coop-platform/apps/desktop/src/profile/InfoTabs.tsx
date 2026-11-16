import { HISTORY_PAGE, getMemberBilling, getMemberDetails, getMemberHistory, getMemberTemplate, type Lookups, type Member } from '../api'
import { useEffect, useRef, useState } from 'react'
import { EmptyState, Field, Section, SelectField, TableCard } from './fields'
import { Alert, Box, Button, Skeleton, Table, TableBody, TableCell, TableHead, TableRow, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material'
import { memberNo, statusOf, type Id } from '../clients'
import { fmtDate, formatField, when, yearsSince } from './format'
import { useEdit } from './editing'
import { PhAddressFields, StreetField } from './PhAddress'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { FiInfo } from 'react-icons/fi'

/**
 * The address, laid out as it is written on an envelope, one line each: house, unit, block and lot number beside the street name; subdivision, village or building; barangay;
 * city or municipality beside the postal code; province. The same lines serve the present and the provincial address. Living-here-since, residence type and mailing address
 * belong to the present address and follow below.
 */
function AddressSection({ m, lk }: { m: Member; lk?: Lookups }) {
  const [which, setWhich] = useState<'present' | 'provincial'>('present')
  const { editing, draft } = useEdit()
  const present = which === 'present'
  const a = present
    ? { house: m.houseNo, street: m.street, subdivision: m.subdivision, barangay: m.barangay, city: m.city, zip: m.zipCode, province: m.province }
    : { house: m.provHouseNo, street: m.provStreet, subdivision: m.provSubdivision, barangay: m.provBarangay, city: m.provCity, zip: m.provZipCode, province: m.provProvince }
  const col = (res: string, prov: string) => (present ? res : prov)
  const placeCols = { province: col('ResAddProvince', 'ProvAddProvince'), city: col('ResAddCity', 'ProvAddCity'), barangay: col('ResAddBarangay', 'ProvAddBarangay') }
  // The fields on a row start out the same width. A field changes size only when what is written in it no longer fits its own box: then it takes a larger share of the
  // room the row has left, and the others give way a little, never below their minimum. Short text changes nothing, however it is typed. A field wraps onto a new line
  // only when even the minimums do not fit.
  const rowRef = useRef<HTMLDivElement>(null)
  const [rowWidth, setRowWidth] = useState(0)
  useEffect(() => {
    const el = rowRef.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const watch = new ResizeObserver(() => setRowWidth(el.clientWidth))
    watch.observe(el)
    return () => watch.disconnect()
  }, [])
  const GAP = 16
  const POSTAL = 110
  const MIN = 160
  /** How many characters fit in one of the three equal boxes of a row (about 7.3px a character at 14px, less the box's padding); 28 until the row is measured. */
  const fits = (others: number) => (rowWidth > 0 ? Math.max(8, Math.floor(((rowWidth - others - 2 * GAP) / 3 - 32) / 7.3)) : 28)
  const text = (col: string | undefined, stored: string | null | undefined) => (col && draft[col] !== undefined ? draft[col] : stored ?? '')
  const cell = (value: string, others = 0) => ({ flex: `${1 + Math.max(0, value.length - fits(others)) / 2} 1 ${MIN}px`, minWidth: MIN })
  const rowSx = { gridColumn: '1 / -1', display: 'flex', flexWrap: 'wrap', gap: '12px 16px' } as const
  const c = {
    house: col('ResAddHouseNo', 'ProvAddHouseNo'), street: col('ResAddStreet', 'ProvAddStreet'), sub: col('ResAddSubdivision', 'ProvAddSubdivision'),
    zip: col('ResAddZipCode', 'ProvAddZipCode'),
  }
  return (
    <Section
      title="Address"
      action={
        <ToggleButtonGroup size="small" exclusive value={which} onChange={(_, v) => v && setWhich(v)} aria-label="Address type"
          sx={{ '& .MuiToggleButton-root': { py: 0.25, px: 1.25, fontSize: 12, textTransform: 'none' } }}>
          <ToggleButton value="present">Present</ToggleButton>
          <ToggleButton value="provincial">Provincial</ToggleButton>
        </ToggleButtonGroup>
      }
    >
      <Box sx={rowSx} ref={rowRef}>
        <Box sx={cell(text(c.house, a.house))}><Field label="House / Unit / Block & Lot Number" value={a.house} col={c.house} /></Box>
        <Box sx={cell(text(c.street, a.street))}>
          {editing
            ? <StreetField label="Street Name" col={c.street} stored={a.street} place={{ cols: placeCols, stored: { province: a.province, city: a.city, barangay: a.barangay } }} />
            : <Field label="Street Name" value={a.street} col={c.street} />}
        </Box>
        <Box sx={cell(text(c.sub, a.subdivision))}><Field label="Subdivision / Village / Building Name" value={a.subdivision} col={c.sub} /></Box>
      </Box>
      <Box sx={rowSx}>
        {editing ? (
          <PhAddressFields cols={placeCols} stored={{ province: a.province, city: a.city, barangay: a.barangay }}>
            {({ province, city, barangay }) => (
              <>
                <Box sx={cell(text(placeCols.barangay, a.barangay), POSTAL + GAP)}>{barangay}</Box>
                <Box sx={cell(text(placeCols.city, a.city), POSTAL + GAP)}>{city}</Box>
                <Box sx={cell(text(placeCols.province, a.province), POSTAL + GAP)}>{province}</Box>
              </>
            )}
          </PhAddressFields>
        ) : (
          <>
            <Box sx={cell(text(placeCols.barangay, a.barangay), POSTAL + GAP)}><Field label="Barangay" value={a.barangay} col={placeCols.barangay} /></Box>
            <Box sx={cell(text(placeCols.city, a.city), POSTAL + GAP)}><Field label="City or Municipality" value={a.city} col={placeCols.city} /></Box>
            <Box sx={cell(text(placeCols.province, a.province), POSTAL + GAP)}><Field label="Province" value={a.province} col={placeCols.province} /></Box>
          </>
        )}
        <Box sx={{ flex: `0 0 ${POSTAL}px`, minWidth: POSTAL }}><Field label="Postal Code" value={a.zip} col={c.zip} /></Box>
      </Box>
      {present && (
        <>
          <Field label="Living here since" value={m.residenceSince} col="ResSince" kind="year" />
          <SelectField label="Residence type" lookup="residenceType" code={m.residenceType} lk={lk} col="ResType" />
          <SelectField label="Mailing address" lookup="mailingAddress" code={m.mailingAddress} lk={lk} col="MailingAddress" />
        </>
      )}
    </Section>
  )
}

export function MembershipTab({ m, lk }: { m: Member; lk?: Lookups }) {
  const st = statusOf(m, lk)
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr)', gap: '12px', alignItems: 'start' }}>
      <Section title="Membership">
        <Field label="Member no." value={m.id.clientId === 0 ? 'Given on approval' : memberNo(m.id)} />
        <Field label="Status" value={m.id.clientId === 0 ? 'New' : st.label} />
        <Field label="Date opened" value={fmtDate(m.dateOpened)} />
        <Field label="Date closed" value={fmtDate(m.closedDate)} />
        <SelectField label="Membership type" lookup="clientType" code={m.clientType} lk={lk} col="ClientType" />
      </Section>
      <Section title="Classification">
        <SelectField label="Branch" lookup="department" code={m.department} lk={lk} col="ClientDEPT" />
      </Section>
    </Box>
  )
}

export function PersonalTab({ m, lk }: { m: Member; lk?: Lookups }) {
  const age = yearsSince(m.dateOfBirth)
  return (
    <Box>
      <Section title="Identity">
        {/* The four name fields always share one line (Suffix is the short one); the rest of the section flows under them. */}
        <Box sx={{ gridColumn: '1 / -1', display: 'grid', gap: '12px 16px', gridTemplateColumns: 'minmax(0, 2fr) minmax(0, 2fr) minmax(0, 2fr) minmax(0, 1fr)' }}>
          <Field label="Last name" value={m.lastName} col="LName" />
          <Field label="First name" value={m.firstName} col="FName" />
          <Field label="Middle name" value={m.middleName} col="MName" />
          <Field label="Suffix" value={m.suffix} col="SName" />
        </Box>
        <Field label="Date of birth" value={m.dateOfBirth ? `${fmtDate(m.dateOfBirth)}${age != null ? ` · ${age} yrs` : ''}` : null} col="DateOfBirth" raw={m.dateOfBirth} kind="date" />
        <SelectField label="Gender" lookup="gender" code={m.gender} lk={lk} col="Gender" />
        <SelectField label="Civil status" lookup="civilStatus" code={m.civilStatus} lk={lk} col="CivilStatus" />
      </Section>

      <Section title="Contact">
        <Field label="Mobile" value={m.cellNumber} col="CellNum" />
        <Field label="Email" value={m.email} col="EmailAddress" />
      </Section>

      <AddressSection m={m} lk={lk} />
    </Box>
  )
}

/** A tab made of labelled sections, read from the details endpoint. Sections with nothing recorded are left out. */
export function DetailsTab({ id, tab, title, lk }: { id: Id; tab: string; title: string; lk?: Lookups }) {
  const { editing } = useEdit()
  const { data, error, isLoading } = useQuery({
    queryKey: ['member-details', id],
    queryFn: () => (id.clientId === 0 ? getMemberTemplate() : getMemberDetails(id.branchCode, id.clientId)),
    staleTime: 60_000,
  })
  if (error) return <Alert severity="error">{(error as Error).message}</Alert>
  if (isLoading) return <Skeleton variant="rounded" height={180} />
  // While editing, empty fields are shown too so a value can be added where there is none yet.
  const sections = (data?.[tab] ?? []).filter((s) => editing || s.fields.some((f) => f.value != null))
  if (sections.length === 0) {
    return <EmptyState icon={<FiInfo />} title="Nothing recorded" hint={`There is no ${title.toLowerCase()} information for this client.`} />
  }
  const card = (sec: (typeof sections)[number]) => (
    <Section key={sec.title} title={sec.title}>
      {sec.fields.map((f) => {
        const v = formatField(f)
        const wide = f.label === 'Remarks' || (v?.length ?? 0) > 60
        return (
          <Box key={f.label} sx={{ gridColumn: wide ? '1 / -1' : undefined, minWidth: 0 }}>
            {f.lookup
              ? <SelectField label={f.label} lookup={f.lookup} code={f.code} lk={lk} col={f.editable ? f.key : undefined} />
              : <Field label={f.label} value={v} col={f.editable ? f.key : undefined} raw={f.value} kind={f.kind} />}
          </Box>
        )
      })}
    </Section>
  )
  // Every section is a full-width card, one under the other. Business / Employment keeps its order: the job and its address first, then Work & income and Income.
  if (tab === 'business') {
    const right = new Set(['Work & income', 'Income'])
    return (
      <Box>
        {sections.filter((s) => !right.has(s.title)).map(card)}
        {sections.filter((s) => right.has(s.title)).map(card)}
      </Box>
    )
  }
  return <Box>{sections.map(card)}</Box>
}

/** Who did what to this client, newest first, loaded a page at a time. */
export function HistoryTab({ id }: { id: Id }) {
  const q = useInfiniteQuery({
    queryKey: ['member-history', id],
    queryFn: ({ pageParam }) => getMemberHistory(id.branchCode, id.clientId, pageParam),
    initialPageParam: 0,
    getNextPageParam: (last, pages) => (last.length >= HISTORY_PAGE ? pages.length : undefined),
  })
  if (q.error) return <Alert severity="error">{(q.error as Error).message}</Alert>
  if (q.isLoading) return <Skeleton variant="rounded" height={220} />
  const rows = q.data?.pages.flat() ?? []
  if (rows.length === 0) return <EmptyState icon={<FiInfo />} title="No history" hint="No activity has been logged for this client." />
  return (
    <>
      <TableCard>
        <Table size="small" aria-label="Client activity history">
          <TableHead><TableRow><TableCell>When</TableCell><TableCell>User</TableCell><TableCell>Activity</TableCell><TableCell>Details</TableCell></TableRow></TableHead>
          <TableBody>
            {rows.map((r, i) => (
              <TableRow key={`${r.at}-${i}`} hover>
                <TableCell sx={{ whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums' }}>{when(r.at)}</TableCell>
                <TableCell>{r.user ?? '—'}</TableCell>
                <TableCell>{r.activity ?? '—'}</TableCell>
                <TableCell>
                  {r.info}
                  {(r.from || r.to) && <Typography component="div" sx={{ fontSize: 12 }} color="text.secondary">{r.from ?? '—'} → {r.to ?? '—'}</Typography>}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableCard>
      {q.hasNextPage && (
        <Box sx={{ textAlign: 'center', mt: 1.5 }}>
          <Button size="small" variant="outlined" onClick={() => q.fetchNextPage()} disabled={q.isFetchingNextPage}>
            {q.isFetchingNextPage ? 'Loading…' : 'Load older activity'}
          </Button>
        </Box>
      )}
    </>
  )
}

export function BillingTab({ id }: { id: Id }) {
  const { data, error, isLoading } = useQuery({ queryKey: ['member-billing', id], queryFn: () => getMemberBilling(id.branchCode, id.clientId), staleTime: 60_000 })
  if (error) return <Alert severity="error">{(error as Error).message}</Alert>
  if (isLoading) return <Skeleton variant="rounded" height={160} />
  if (!data || data.length === 0) return <EmptyState icon={<FiInfo />} title="No billing records" hint="Nothing has been billed to this client." />
  return (
    <TableCard>
      <Table size="small" aria-label="Billing">
        <TableHead><TableRow><TableCell>Date</TableCell><TableCell>Reference</TableCell><TableCell>Policy</TableCell><TableCell>Type</TableCell><TableCell>Status</TableCell><TableCell align="right">Amount</TableCell></TableRow></TableHead>
        <TableBody>
          {data.map((b, i) => (
            <TableRow key={`${b.reference}-${i}`} hover>
              <TableCell>{fmtDate(b.date) ?? '—'}</TableCell><TableCell>{b.reference ?? '—'}</TableCell><TableCell>{b.policy ?? '—'}</TableCell>
              <TableCell>{b.type ?? '—'}</TableCell><TableCell>{b.status ?? '—'}</TableCell>
              <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{b.amount ? Number(b.amount).toLocaleString(undefined, { minimumFractionDigits: 2 }) : '—'}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableCard>
  )
}
