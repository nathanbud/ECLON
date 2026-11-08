import { HISTORY_PAGE, getMemberBilling, getMemberDetails, getMemberHistory, getMemberTemplate, type Lookups, type Member } from '../api'
import { useState } from 'react'
import { EmptyState, Field, Section, SelectField, TableCard } from './fields'
import { Alert, Box, Button, Skeleton, Table, TableBody, TableCell, TableHead, TableRow, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material'
import { memberNo, statusOf, type Id } from '../clients'
import { fmtDate, formatField, when, yearsSince } from './format'
import { useEdit } from './editing'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { FiInfo } from 'react-icons/fi'

/**
 * The address, laid out as it is written on an envelope, one line each: house, unit, block and lot number beside the street name; subdivision, village or building; barangay;
 * city or municipality beside the postal code; province. The same lines serve the present and the provincial address. Living-here-since, residence type and mailing address
 * belong to the present address and follow below.
 */
function AddressSection({ m, lk }: { m: Member; lk?: Lookups }) {
  const [which, setWhich] = useState<'present' | 'provincial'>('present')
  const present = which === 'present'
  const a = present
    ? { house: m.houseNo, street: m.street, subdivision: m.subdivision, barangay: m.barangay, city: m.city, zip: m.zipCode, province: m.province }
    : { house: m.provHouseNo, street: m.provStreet, subdivision: m.provSubdivision, barangay: m.provBarangay, city: m.provCity, zip: m.provZipCode, province: m.provProvince }
  const col = (res: string, prov: string) => (present ? res : prov)
  const span = (n: number) => ({ gridColumn: `span ${n}`, minWidth: 0 })
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
      <Box sx={{ gridColumn: '1 / -1', display: 'grid', gap: '6px 12px', gridTemplateColumns: 'repeat(6, minmax(0, 1fr))' }}>
        <Box sx={span(2)}><Field label="House / Unit / Block & Lot Number" value={a.house} col={col('ResAddHouseNo', 'ProvAddHouseNo')} /></Box>
        <Box sx={span(4)}><Field label="Street Name" value={a.street} col={col('ResAddStreet', 'ProvAddStreet')} /></Box>
        <Box sx={span(6)}><Field label="Subdivision / Village / Building Name" value={a.subdivision} col={col('ResAddSubdivision', 'ProvAddSubdivision')} /></Box>
        <Box sx={span(6)}><Field label="Barangay" value={a.barangay} col={col('ResAddBarangay', 'ProvAddBarangay')} /></Box>
        <Box sx={span(4)}><Field label="City or Municipality" value={a.city} col={col('ResAddCity', 'ProvAddCity')} /></Box>
        <Box sx={span(2)}><Field label="Postal Code" value={a.zip} col={col('ResAddZipCode', 'ProvAddZipCode')} /></Box>
        <Box sx={span(6)}><Field label="Province" value={a.province} col={col('ResAddProvince', 'ProvAddProvince')} /></Box>
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
        <Box sx={{ gridColumn: '1 / -1', display: 'grid', gap: '6px 12px', gridTemplateColumns: 'minmax(0, 2fr) minmax(0, 2fr) minmax(0, 2fr) minmax(0, 1fr)' }}>
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
