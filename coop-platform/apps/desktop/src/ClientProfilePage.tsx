import {
  Alert, Avatar, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, Divider, ListItemIcon, ListItemText, Menu, MenuItem, Skeleton, Tab, Table, TableBody,
  Snackbar, TableCell, TableHead, TableRow, Tabs, TextField,
  ToggleButton, ToggleButtonGroup, Tooltip, Typography,
} from '@mui/material'
import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query'
import { createContext, useContext, useEffect, useId, useRef, useState, type ReactNode } from 'react'
import {
  FiAlertTriangle, FiCheckCircle, FiChevronDown, FiClock, FiEdit2, FiInfo, FiPlus, FiSave, FiSearch,
  FiSlash, FiTrash2, FiX,
} from 'react-icons/fi'
import { IoFingerPrint } from 'react-icons/io5'
import {
  getCapabilities, getLookups, getMember, getMemberBilling, getMemberDetails, getMemberHistory, getMemberImage, askFoOverride, checkSavingsWithdrawal, getSavings, getSavingsHistory, postSavingsDeposit, postSavingsWithdrawal, getShareHistory, getShares, postSharePayment, postShareWithdrawal, type SharePaymentReceipt, type ShareWithdrawalOut, getPendingChange, HISTORY_PAGE, approveChange, rejectChange, submitChange, withdrawChange,
  type Change, type DetailField, type Lookups, type Member, type SavingsAccount, type SavingsReceipt, type SavingsWithdrawalCheck, type ShareAccount,
} from './api'
import { appBackground, glass } from './glass'
import { tileFill } from './theme'
import { displayName, initials, memberNo, statusOf, type Id } from './clients'
import LoansTab from './LoansTab'
import FindClientDialog, { FindBody } from './FindClientDialog'
import { useMe } from './session'
import { ToolbarPortal } from './toolbarSlot'

const TABS = [
  'Personal', 'Business / Employment', 'Education', 'Contacts & IDs', 'Spouse Info', 'Groups',
  'History', 'Closed', 'Pledges / Insurances', 'Others', 'Sketch', 'Billing', 'Share Capital', 'Savings', 'Loans',
]

// ---- formatting helpers -------------------------------------------------------------------------------------------

const parseDate = (iso: string) => new Date(`${iso.slice(0, 10)}T00:00:00`)
const fmtDate = (iso?: string | null) =>
  iso ? parseDate(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }) : null
const yearsSince = (iso?: string | null) => {
  if (!iso) return null
  const d = parseDate(iso)
  const now = new Date()
  let y = now.getFullYear() - d.getFullYear()
  if (now.getMonth() < d.getMonth() || (now.getMonth() === d.getMonth() && now.getDate() < d.getDate())) y--
  return y
}

// ---- small building blocks ----------------------------------------------------------------------------------------

// ---- editing ------------------------------------------------------------------------------------------------------

// Values live in input fields. They stay locked until the page is in edit mode; then every field that is bound to a
// column (it has a `col`) unlocks, and what you type is collected in `draft`, keyed by column name.
interface EditState { editing: boolean; draft: Record<string, string>; set: (col: string, value: string, original: string) => void }
const EditCtx = createContext<EditState>({ editing: false, draft: {}, set: () => {} })
const useEdit = () => useContext(EditCtx)

type Value = string | number | null | undefined
type Kind = 'text' | 'date' | 'year' | 'money'

/** What a field needs to be editable: the column it writes to, the stored value it started from, and its type. */
interface Bind { col?: string; raw?: Value; kind?: Kind }

function useBound({ col, raw, kind = 'text' }: Bind) {
  const { editing, draft, set } = useEdit()
  const live = editing && !!col
  const stored = raw == null ? '' : String(raw)
  const original = kind === 'date' ? stored.slice(0, 10) : stored
  return {
    live,
    current: live ? draft[col!] ?? original : original,
    change: (v: string) => set(col!, v, original),
  }
}

const fieldSx = (live: boolean) => ({
  '& .MuiOutlinedInput-root': { fontSize: 14, backgroundColor: live ? 'background.paper' : 'transparent' },
  '& .MuiOutlinedInput-notchedOutline': { borderColor: live ? 'text.disabled' : 'divider' },
  '& .MuiInputLabel-root': { fontSize: 13 },
  ...(live ? {} : { '& .MuiOutlinedInput-root:hover .MuiOutlinedInput-notchedOutline': { borderColor: 'divider' } }),
}) as const

function Field({ label, value, col, raw, kind = 'text' }: { label: string; value?: Value } & Bind) {
  const { live, current, change } = useBound({ col, raw: raw === undefined ? value : raw, kind })
  const text = live ? current : value == null ? '' : String(value)
  return (
    <TextField
      label={label} value={text} placeholder="—" fullWidth
      multiline={!live || kind === 'text'} maxRows={4}
      type={live && kind === 'date' ? 'date' : undefined}
      onChange={live ? (e) => change(e.target.value) : undefined}
      slotProps={{
        input: { readOnly: !live },
        inputLabel: { shrink: true },
        htmlInput: live && (kind === 'year' || kind === 'money') ? { inputMode: kind === 'year' ? 'numeric' : 'decimal' } : {},
      }}
      sx={fieldSx(live)}
    />
  )
}

/** A field with its label beside it, for compact lists such as the header. */
function InlineField({ label, value }: { label: string; value?: Value }) {
  const id = useId()
  return (
    <>
      <Typography component="label" htmlFor={id} sx={{ fontSize: 11.5, fontWeight: 500 }} color="text.secondary">{label}</Typography>
      <TextField
        id={id} hiddenLabel size="small" value={value == null ? '' : String(value)} placeholder="—" fullWidth
        slotProps={{ input: { readOnly: true }, htmlInput: { title: value == null ? undefined : String(value) } }}
        sx={{ ...fieldSx(false), '& .MuiOutlinedInput-input': { py: '4px', fontSize: 13 } }}
      />
    </>
  )
}

function Section({ title, children, action }: { title: string; children: ReactNode; action?: ReactNode }) {
  return (
    <Box sx={(t) => ({ p: 1.5, mb: 1.5, breakInside: 'avoid', ...glass(t) })}>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1, minHeight: 24 }}>
        <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase' }} color="text.secondary">
          {title}
        </Typography>
        {action}
      </Box>
      <Box sx={{ display: 'grid', gap: '12px 14px', gridTemplateColumns: 'repeat(auto-fill, minmax(132px, 1fr))' }}>{children}</Box>
    </Box>
  )
}

function EmptyState({ icon, title, hint, action }: { icon: ReactNode; title: string; hint: string; action?: ReactNode }) {
  return (
    <Box sx={{ height: '100%', minHeight: 260, display: 'grid', placeItems: 'center', textAlign: 'center', p: 4 }}>
      <Box>
        <Box sx={{ fontSize: 36, color: 'text.disabled', display: 'flex', justifyContent: 'center', mb: 1 }}>{icon}</Box>
        <Typography sx={{ fontWeight: 600 }}>{title}</Typography>
        <Typography sx={{ fontSize: 13 }} color="text.secondary">{hint}</Typography>
        {action && <Box sx={{ mt: 2 }}>{action}</Box>}
      </Box>
    </Box>
  )
}

// ---- profile ------------------------------------------------------------------------------------------------------

/**
 * A coded field shown as a dropdown of its lookup list. Locked until edit mode, when it opens. A stored code with no
 * description still shows, as "Code n".
 */
function SelectField({ label, lookup, code, lk, inline = false, col }: {
  label: string; lookup: string; code: number | string | null | undefined; lk?: Lookups; inline?: boolean; col?: string
}) {
  const id = useId()
  const { live, current, change } = useBound({ col, raw: code })
  const options = Object.entries(lk?.[lookup] ?? {})
  const value = current
  const known = value === '' || options.some(([k]) => k === value)
  const sx = {
    ...fieldSx(live),
    '& .MuiSelect-icon': { opacity: live ? 1 : 0.35 },
    ...(inline ? { '& .MuiSelect-select': { py: '4px', fontSize: 13 } } : {}),
  }
  const items = [
    <MenuItem key="" value=""><em>—</em></MenuItem>,
    ...options.map(([k, text]) => <MenuItem key={k} value={k}>{text}</MenuItem>),
    ...(known ? [] : [<MenuItem key={value} value={value}>{`Code ${value}`}</MenuItem>]),
  ]
  const select = { displayEmpty: true, renderValue: (v: unknown) => (v === '' ? <span style={{ opacity: 0.5 }}>—</span> : options.find(([k]) => k === v)?.[1] ?? `Code ${String(v)}`) }
  const onChange = live ? (e: React.ChangeEvent<HTMLInputElement>) => change(e.target.value) : undefined
  const field = inline
    ? <TextField id={id} select hiddenLabel size="small" value={value} fullWidth sx={sx} onChange={onChange} slotProps={{ input: { readOnly: !live }, select }}>{items}</TextField>
    : <TextField select label={label} value={value} fullWidth sx={sx} onChange={onChange} slotProps={{ input: { readOnly: !live }, inputLabel: { shrink: true }, select }}>{items}</TextField>
  if (!inline) return field
  return (
    <>
      <Typography component="label" htmlFor={id} sx={{ fontSize: 11.5, fontWeight: 500 }} color="text.secondary">{label}</Typography>
      {field}
    </>
  )
}

function AddressSection({ m, lk }: { m: Member; lk?: Lookups }) {
  const [which, setWhich] = useState<'present' | 'provincial'>('present')
  const a = which === 'present'
    ? { street: m.street, barangay: m.barangay, city: m.city, province: m.province }
    : { street: m.provStreet, barangay: m.provBarangay, city: m.provCity, province: m.provProvince }
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
      <Field label="Street" value={a.street} col={which === 'present' ? 'ResAddStreet' : 'ProvAddStreet'} />
      <Field label="Barangay" value={a.barangay} col={which === 'present' ? 'ResAddBarangay' : 'ProvAddBarangay'} />
      <Field label="City / Municipality" value={a.city} col={which === 'present' ? 'ResAddCity' : 'ProvAddCity'} />
      <Field label="Province" value={a.province} col={which === 'present' ? 'ResAddProvince' : 'ProvAddProvince'} />
      {which === 'present' && (
        <>
          <Field label="Zip code" value={m.zipCode} col="ResAddZipCode" />
          <Field label="Living here since" value={m.residenceSince} col="ResSince" kind="year" />
          <SelectField label="Residence type" lookup="residenceType" code={m.residenceType} lk={lk} col="ResType" />
          <SelectField label="Mailing address" lookup="mailingAddress" code={m.mailingAddress} lk={lk} col="MailingAddress" />
        </>
      )}
    </Section>
  )
}

function PersonalTab({ m, lk }: { m: Member; lk?: Lookups }) {
  const age = yearsSince(m.dateOfBirth)
  return (
    <Box sx={{ columns: '340px', columnGap: '12px' }}>
      <Section title="Identity">
        <Field label="Last name" value={m.lastName} col="LName" />
        <Field label="First name" value={m.firstName} col="FName" />
        <Field label="Middle name" value={m.middleName} col="MName" />
        <Field label="Suffix" value={m.suffix} col="SName" />
        <Field label="Date of birth" value={m.dateOfBirth ? `${fmtDate(m.dateOfBirth)}${age != null ? ` · ${age} yrs` : ''}` : null} col="DateOfBirth" raw={m.dateOfBirth} kind="date" />
        <SelectField label="Gender" lookup="gender" code={m.gender} lk={lk} col="Gender" />
        <SelectField label="Civil status" lookup="civilStatus" code={m.civilStatus} lk={lk} col="CivilStatus" />
        <SelectField label="Religion" lookup="religion" code={m.religion} lk={lk} col="ReligionID" />
      </Section>

      <Section title="Membership">
        <Field label="Date closed" value={fmtDate(m.closedDate)} />
        <SelectField label="DOSRI tag" lookup="dosriTag" code={m.dosriTag} lk={lk} col="DOSRITag" />
        <SelectField label="Health care" lookup="yesNo" code={m.includeHealthCare ?? 0} lk={lk} col="IsIncludeHealthCare" />
      </Section>

      <Section title="Contact">
        <Field label="Mobile" value={m.cellNumber} col="CellNum" />
        <Field label="Email" value={m.email} col="EmailAddress" />
      </Section>

      <Section title="Work & income">
        <Field label="Profession" value={m.profession} col="Profession" />
        <Field label="Monthly income" value={m.monthlyIncome ? Number(m.monthlyIncome).toLocaleString(undefined, { minimumFractionDigits: 2 }) : null} col="BusinessIncome" raw={m.monthlyIncome} kind="money" />
      </Section>

      <AddressSection m={m} lk={lk} />

      <Section title="Physical">
        <Field label="Height" value={m.height} col="Height" />
        <Field label="Weight" value={m.weight} col="Weight" />
        <Field label="Blood type" value={m.bloodType} col="BloodType" />
      </Section>
    </Box>
  )
}

/** Loads a member's photo or signature; resolves to null when none is on file. */
function useMemberImage(kind: 'photo' | 'signature', id: Id) {
  const { data } = useQuery({
    queryKey: ['member-image', kind, id],
    queryFn: () => getMemberImage(kind, id.branchCode, id.clientId),
    staleTime: 5 * 60_000,
    retry: false,
  })
  // Object URLs hold the image in memory, so release each one when it is replaced or the page closes.
  useEffect(() => () => { if (data) URL.revokeObjectURL(data) }, [data])
  return data ?? null
}

/** Small, low-contrast tag: short, thin outline, regular weight. */
const quietChip = {
  height: 20, fontSize: 11.5, fontWeight: 400, opacity: 0.9,
  '& .MuiChip-icon': { fontSize: 12, ml: 0.75, mr: -0.25 },
  '& .MuiChip-label': { px: 0.9 },
} as const

function ProfileHeader({ m, lk }: { m: Member; lk?: Lookups }) {
  const { editing } = useEdit()
  const st = statusOf(m, lk)
  const photo = useMemberImage('photo', m.id)
  const signature = useMemberImage('signature', m.id)
  const alertLabel = m.alertLevel != null ? lk?.alertLevel?.[m.alertLevel] ?? `Level ${m.alertLevel}` : null
  const alertOk = m.alertLevel === 1
  return (
    <Box sx={(t) => ({ display: 'flex', alignItems: 'center', gap: 2, p: 1.75, ...glass(t, { elevated: true }) })}>
      <Avatar variant="rounded" src={photo ?? undefined} alt={`Photo of ${displayName(m)}`} sx={{
        width: 68, height: 68, fontSize: 24, fontWeight: 600, color: '#fff', borderRadius: '5px', flexShrink: 0,
        background: `linear-gradient(160deg, ${tileFill.orange}F2, ${tileFill.orange})`,
        boxShadow: '0 1px 2px rgba(0,0,0,0.14)',
      }}>{initials(m)}</Avatar>
      <Box sx={{ minWidth: 0, flex: 1 }}>
        {/* Status and alert: small outlined tags above the name, kept quiet so the name leads. */}
        <Box role="group" aria-label="Client status" sx={{ display: 'flex', gap: 0.75, mb: 0.75, flexWrap: 'wrap' }}>
          {editing && <Chip size="small" color="primary" label="Editing" icon={<FiEdit2 />} sx={quietChip} />}
          <Chip size="small" variant="outlined" color={st.kind === 'default' ? undefined : st.kind} label={st.label}
            icon={st.kind === 'default' ? <FiSlash /> : <FiCheckCircle />} sx={quietChip} />
          {alertLabel && (
            <Chip size="small" variant="outlined" color={alertOk ? 'success' : (m.alertLevel ?? 0) >= 6 ? 'error' : 'warning'}
              label={`Alert: ${alertLabel}`} icon={alertOk ? <FiCheckCircle /> : <FiAlertTriangle />} sx={quietChip} />
          )}
        </Box>
        <Typography component="h2" noWrap sx={{ fontSize: 22, fontWeight: 700, letterSpacing: '-0.02em', lineHeight: 1.2 }}>{displayName(m)}</Typography>
        <Typography sx={{ fontSize: 13.5, fontVariantNumeric: 'tabular-nums', mt: 0.25 }} color="text.secondary">
          {memberNo(m.id)}{m.referenceId ? ` · Ref ${m.referenceId}` : ''}
        </Typography>
      </Box>
      {signature && (
        <Box sx={{ display: { xs: 'none', lg: 'block' }, textAlign: 'center', flexShrink: 0 }}>
          {/* Signatures are dark ink on a light background, so they always sit on a white surface, in both themes. */}
          <Box sx={{ bgcolor: '#fff', borderRadius: '5px', border: 1, borderColor: 'divider', p: 0.75, width: 200, height: 84, display: 'grid', placeItems: 'center' }}>
            <Box component="img" src={signature} alt={`Signature of ${displayName(m)}`} sx={{ maxWidth: '100%', maxHeight: '100%', objectFit: 'contain' }} />
          </Box>
          <Typography sx={{ fontSize: 11.5, mt: 0.5 }} color="text.secondary">Signature</Typography>
        </Box>
      )}
      {/* Right edge: fingerprint and classification, stacked in a column. */}
      <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'stretch', gap: 1, flexShrink: 0 }}>
        {/* Placeholder: fingerprints are not in the database, so there is nothing to open yet. */}
        <Tooltip title="No fingerprint on file. The fingerprint source is not connected yet.">
          <span style={{ display: 'flex' }}>
            <Chip size="small" variant="outlined" disabled icon={<IoFingerPrint />} label="Fingerprint" aria-label="Fingerprint (not available)"
              sx={{ justifyContent: 'flex-start', flex: 1 }} />
          </span>
        </Tooltip>
        {/* Classification sits with the status: client type, account type and department. */}
        <Box role="group" aria-label="Client classification" sx={{ display: 'grid', gridTemplateColumns: 'auto minmax(0, 1fr)', gap: '4px 10px', alignItems: 'center', mt: 0, width: 300 }}>
          <SelectField inline label="Client type" lookup="clientType" code={m.clientType} lk={lk} col="ClientType" />
          <SelectField inline label="Account type" lookup="accountType" code={m.accountType} lk={lk} col="AccountType" />
          <SelectField inline label="Department" lookup="department" code={m.department} lk={lk} col="ClientDEPT" />
          <InlineField label="Date opened" value={m.dateOpened ? `${fmtDate(m.dateOpened)}${yearsSince(m.dateOpened) != null ? ` · ${yearsSince(m.dateOpened)} yrs` : ''}` : null} />
        </Box>
      </Box>
    </Box>
  )
}

const isMac = /Mac|iPhone|iPad/.test(navigator.platform)
const MOD = isMac ? '⌘' : 'Ctrl+'
const SHORTCUT_FIND = `${MOD}F`

// Record actions with their shortcuts. The page supplies `run` only while an action is possible, which keeps the
// others disabled and leaves their keys free for the browser.
interface RecordAction { label: string; icon: ReactNode; shortcut: string; match: (e: KeyboardEvent) => boolean; run?: () => void }
const mod = (e: KeyboardEvent) => (isMac ? e.metaKey : e.ctrlKey)
const ACTIONS: RecordAction[] = [
  { label: 'New', icon: <FiPlus />, shortcut: `${MOD}N`, match: (e) => mod(e) && e.key === 'n' },
  { label: 'Edit', icon: <FiEdit2 />, shortcut: `${MOD}E`, match: (e) => mod(e) && e.key === 'e' },
  { label: 'Delete', icon: <FiTrash2 />, shortcut: `${MOD}Del`, match: (e) => mod(e) && e.key === 'Delete' },
  { label: 'Save', icon: <FiSave />, shortcut: `${MOD}S`, match: (e) => mod(e) && e.key === 's' },
  { label: 'Cancel', icon: <FiX />, shortcut: 'Esc', match: (e) => e.key === 'Escape' },
]

const Shortcut = ({ children }: { children: ReactNode }) => (
  <Typography component="span" sx={{ ml: 4, fontSize: 12, fontVariantNumeric: 'tabular-nums' }} color="text.secondary">{children}</Typography>
)

/** The "Client Profile" menu in the title bar: find plus the record actions, each with its shortcut. */
function ClientMenu({ onFind, actions, editOn, pendingNote }: { onFind: () => void; actions: RecordAction[]; editOn: boolean; pendingNote: string | null }) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const button = useRef<HTMLButtonElement>(null)
  const close = () => setAnchor(null)

  // Alt+C opens the menu, like a menu-bar mnemonic.
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      if (e.altKey && !e.ctrlKey && !e.metaKey && e.key.toLowerCase() === 'c') {
        e.preventDefault()
        setAnchor(button.current)
      }
    }
    window.addEventListener('keydown', on)
    return () => window.removeEventListener('keydown', on)
  }, [])

  const item = (a: RecordAction) => (
    <MenuItem key={a.label} disabled={!a.run} onClick={() => { close(); a.run?.() }}>
      <ListItemIcon sx={{ minWidth: 32, fontSize: 16 }}>{a.icon}</ListItemIcon>
      <ListItemText slotProps={{ primary: { sx: { fontSize: 13 } } }}>{a.label}</ListItemText>
      <Shortcut>{a.shortcut}</Shortcut>
    </MenuItem>
  )
  return (
    <>
      <Tooltip title="Client Profile menu (Alt+C)">
        <Button
          ref={button} size="small" onClick={(e) => setAnchor(e.currentTarget)} endIcon={<FiChevronDown />}
          aria-haspopup="menu" aria-expanded={!!anchor} aria-keyshortcuts="Alt+C"
          sx={{ color: 'inherit', fontSize: 13, fontWeight: 400, px: 0.75, py: 0.25, minWidth: 0, borderRadius: '5px', '& .MuiButton-endIcon': { ml: 0.25 }, '&:hover': { bgcolor: 'action.hover' } }}
        >
          <u>C</u>lient Profile
        </Button>
      </Tooltip>
      <Menu anchorEl={anchor} open={!!anchor} onClose={close} slotProps={{ paper: { sx: { borderRadius: '5px', minWidth: 270, mt: 0.5 } } }}>
        <MenuItem onClick={() => { close(); onFind() }}>
          <ListItemIcon sx={{ minWidth: 32, fontSize: 16 }}><FiSearch /></ListItemIcon>
          <ListItemText slotProps={{ primary: { sx: { fontSize: 13 } } }}>Find Client</ListItemText>
          <Shortcut>{SHORTCUT_FIND}</Shortcut>
        </MenuItem>
        <Divider />
        {actions.slice(0, 3).map(item)}
        <Divider />
        {actions.slice(3).map(item)}
        {(!editOn || pendingNote) && <Divider />}
        {(!editOn || pendingNote) && (
          <Typography sx={{ px: 2, py: 0.75, fontSize: 12, maxWidth: 270 }} color="text.secondary">
            {pendingNote ?? 'You cannot edit clients with this account, or editing is turned off on this server.'}
          </Typography>
        )}
      </Menu>
    </>
  )
}

// ---- the other tabs -----------------------------------------------------------------------------------------------

// Same order as TABS. A key names a tab of the details endpoint; the rest have their own content.
const TAB_KEYS = ['personal', 'business', 'education', 'contacts', 'spouse', 'groups', 'history', 'closed', 'pledges', 'others', 'sketch', 'billing', 'shares', 'savings', 'loans']

function formatField(f: DetailField): string | null {
  if (f.value == null) return null
  if (f.kind === 'date') return fmtDate(f.value)
  if (f.kind === 'money') {
    const n = Number(f.value)
    return Number.isNaN(n) ? f.value : n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  }
  return f.value
}

/** A tab made of labelled sections, read from the details endpoint. Sections with nothing recorded are left out. */
function DetailsTab({ id, tab, title, lk }: { id: Id; tab: string; title: string; lk?: Lookups }) {
  const { data, error, isLoading } = useQuery({
    queryKey: ['member-details', id],
    queryFn: () => getMemberDetails(id.branchCode, id.clientId),
    staleTime: 60_000,
  })
  if (error) return <Alert severity="error">{(error as Error).message}</Alert>
  if (isLoading) return <Skeleton variant="rounded" height={180} />
  const sections = (data?.[tab] ?? []).filter((s) => s.fields.some((f) => f.value != null))
  if (sections.length === 0) {
    return <EmptyState icon={<FiInfo />} title="Nothing recorded" hint={`There is no ${title.toLowerCase()} information for this client.`} />
  }
  return (
    <Box sx={{ columns: '340px', columnGap: '12px' }}>
      {sections.map((sec) => (
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
      ))}
    </Box>
  )
}

const when = (at: string) => new Date(at.replace(' ', 'T')).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })

/** Glass panel holding a table, so the list tabs match the section cards. */
function TableCard({ children }: { children: ReactNode }) {
  return <Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}>{children}</Box>
}

/** Who did what to this client, newest first, loaded a page at a time. */
function HistoryTab({ id }: { id: Id }) {
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

function BillingTab({ id }: { id: Id }) {
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

const peso = (n: number | null | undefined) => (n == null ? '—' : n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }))

function ShareCapitalTab({ id }: { id: Id }) {
  const [open, setOpen] = useState<{ typeCode: number; refNo: number } | null>(null)
  const [withdrawing, setWithdrawing] = useState<ShareAccount | null>(null)
  const [paying, setPaying] = useState<ShareAccount | null>(null)
  const { data, error, isLoading } = useQuery({ queryKey: ['member-shares', id], queryFn: () => getShares(id.branchCode, id.clientId), staleTime: 60_000 })
  if (error) return <Alert severity="error">{(error as Error).message}</Alert>
  if (isLoading) return <Skeleton variant="rounded" height={160} />
  if (!data || data.accounts.length === 0) return <EmptyState icon={<FiInfo />} title="No share capital" hint="This client has no share capital accounts." />
  return (
    <>
      <TableCard>
        <Table size="small" aria-label="Share capital accounts">
          <TableHead>
            <TableRow>
              <TableCell>Type</TableCell><TableCell>Account no.</TableCell><TableCell>Opened</TableCell><TableCell>Status</TableCell>
              <TableCell>Last activity</TableCell><TableCell align="right">Minimum</TableCell><TableCell align="right">Balance</TableCell><TableCell />
            </TableRow>
          </TableHead>
          <TableBody>
            {data.accounts.map((a) => (
              <TableRow key={`${a.typeCode}-${a.refNo}`} hover sx={{ cursor: 'pointer' }} onClick={() => setOpen({ typeCode: a.typeCode, refNo: a.refNo })}>
                <TableCell>{a.type ?? `Type ${a.typeCode}`}</TableCell><TableCell>{a.refNoText ?? a.refNo}</TableCell>
                <TableCell>{fmtDate(a.opened) ?? '—'}</TableCell><TableCell>{a.statusName}</TableCell><TableCell>{fmtDate(a.lastActivity) ?? '—'}</TableCell>
                <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{peso(a.minimumBalance)}</TableCell>
                <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{peso(a.balance)}</TableCell>
                <TableCell align="right">
                  {a.status === 15 && <Button size="small" onClick={(e) => { e.stopPropagation(); setPaying(a) }}>Post payment</Button>}
                  {a.status === 15 && a.balance > 0 && <Button size="small" onClick={(e) => { e.stopPropagation(); setWithdrawing(a) }}>Withdraw</Button>}
                </TableCell>
              </TableRow>
            ))}
            <TableRow>
              <TableCell colSpan={6} sx={{ fontWeight: 700 }}>Total share capital</TableCell>
              <TableCell align="right" sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>{peso(data.totalBalance)}</TableCell>
              <TableCell />
            </TableRow>
          </TableBody>
        </Table>
      </TableCard>
      {open && <ShareHistoryDialog id={id} typeCode={open.typeCode} refNo={open.refNo} onClose={() => setOpen(null)} />}
      {paying && <SharePaymentDialog id={id} account={paying} onClose={() => setPaying(null)} />}
      {withdrawing && <ShareWithdrawalDialog id={id} account={withdrawing} onClose={() => setWithdrawing(null)} />}
    </>
  )
}

function SharePaymentDialog({ id, account, onClose }: { id: Id; account: ShareAccount; onClose: () => void }) {
  const qc = useQueryClient()
  const [amount, setAmount] = useState('')
  const [receiptNo, setReceiptNo] = useState('')
  const [explanation, setExplanation] = useState('')
  const [receipt, setReceipt] = useState<SharePaymentReceipt | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [askConfirm, setAskConfirm] = useState(false)
  const value = Number(amount)
  const valid = amount.trim() !== '' && Number.isFinite(value) && value > 0

  const submit = async (confirmDuplicate: boolean) => {
    setBusy(true); setError(null)
    try {
      const r = await postSharePayment({ clientId: id.clientId, typeCode: account.typeCode, refNo: account.refNo, amount: value, explanation, receiptNo, confirmDuplicate })
      setReceipt(r)
      qc.invalidateQueries({ queryKey: ['member-shares'] })
      qc.invalidateQueries({ queryKey: ['member-share-history'] })
    } catch (e) {
      const msg = (e as Error).message
      setError(msg)
      setAskConfirm(msg.includes('confirm it'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open onClose={busy ? undefined : onClose} maxWidth="xs" fullWidth>
      <DialogTitle>{receipt ? 'Payment posted' : 'Post a cash payment'}</DialogTitle>
      <DialogContent>
        {!receipt && (
          <Box sx={{ display: 'grid', gap: 1.5, pt: 1 }}>
            <Box sx={{ fontSize: 13 }}>{account.type ?? 'Share capital'} {account.refNoText ?? account.refNo} · balance {peso(account.balance)}</Box>
            <TextField label="Amount" type="number" autoFocus value={amount} onChange={(e) => setAmount(e.target.value)} slotProps={{ htmlInput: { min: 0, step: '0.01' } }} />
            <TextField label="Receipt number (optional)" value={receiptNo} onChange={(e) => setReceiptNo(e.target.value)} helperText="Up to 8 letters or digits. Left empty, the system numbers it." />
            <TextField label="Explanation (optional)" value={explanation} onChange={(e) => setExplanation(e.target.value)} placeholder="SC" />
            <Box sx={{ fontSize: 12 }}>Cash goes into your own drawer and is posted to the ledger at once.</Box>
          </Box>
        )}
        {receipt && (
          <Box sx={{ display: 'grid', gap: 0.5, pt: 1, fontSize: 14 }}>
            <Box>Receipt <strong>{receipt.documentNo}</strong> (control {receipt.controlNo}) dated {receipt.date}</Box>
            <Box>{receipt.clientName}</Box>
            <Box>{receipt.account}</Box>
            <Box>Paid <strong>{peso(receipt.amount)}</strong>, new balance <strong>{peso(receipt.balanceAfter)}</strong></Box>
            <Box>Received by {receipt.cashier}</Box>
          </Box>
        )}
        {error && <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert>}
      </DialogContent>
      <DialogActions>
        {receipt ? <Button onClick={onClose}>Close</Button> : (
          <>
            <Button onClick={onClose} disabled={busy}>Cancel</Button>
            {askConfirm
              ? <Button variant="contained" color="warning" disabled={busy} onClick={() => submit(true)}>Post anyway</Button>
              : <Button variant="contained" disabled={busy || !valid} onClick={() => submit(false)}>Post payment</Button>}
          </>
        )}
      </DialogActions>
    </Dialog>
  )
}

function ShareWithdrawalDialog({ id, account, onClose }: { id: Id; account: ShareAccount; onClose: () => void }) {
  const qc = useQueryClient()
  const [amount, setAmount] = useState('')
  const [explanation, setExplanation] = useState('')
  const [done, setDone] = useState<ShareWithdrawalOut | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const value = Number(amount)
  const valid = amount.trim() !== '' && Number.isFinite(value) && value > 0

  const submit = async () => {
    setBusy(true); setError(null)
    try {
      setDone(await postShareWithdrawal({ clientId: id.clientId, typeCode: account.typeCode, refNo: account.refNo, amount: value, explanation }))
      qc.invalidateQueries({ queryKey: ['loan-releases'] })
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open onClose={busy ? undefined : onClose} maxWidth="xs" fullWidth>
      <DialogTitle>{done ? 'Withdrawal voucher created' : 'Withdraw share capital'}</DialogTitle>
      <DialogContent>
        {!done && (
          <Box sx={{ display: 'grid', gap: 1.5, pt: 1 }}>
            <Box sx={{ fontSize: 13 }}>{account.type ?? 'Share capital'} {account.refNoText ?? account.refNo} · balance {peso(account.balance)}</Box>
            <TextField label="Amount" type="number" autoFocus value={amount} onChange={(e) => setAmount(e.target.value)} slotProps={{ htmlInput: { min: 0, step: '0.01' } }} />
            <TextField label="Explanation (optional)" value={explanation} onChange={(e) => setExplanation(e.target.value)} placeholder="SC WITHDRAWAL" />
            <Box sx={{ fontSize: 12 }}>This creates a Cash Voucher that credits the member's vouchers-payable account. Nothing is posted until two other people approve and post it on the Disburse tab.</Box>
          </Box>
        )}
        {done && (
          <Box sx={{ display: 'grid', gap: 0.5, pt: 1, fontSize: 14 }}>
            <Box>Voucher <strong>{done.documentNo}</strong> (control {done.controlNo}) for {done.clientName}</Box>
            <Box>Withdrawal <strong>{peso(done.amount)}</strong>; capital left after it posts <strong>{peso(done.availableAfter)}</strong></Box>
            <Box>It now waits for approval and posting on the Disburse tab.</Box>
          </Box>
        )}
        {error && <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert>}
      </DialogContent>
      <DialogActions>
        {done ? <Button onClick={onClose}>Close</Button> : (
          <>
            <Button onClick={onClose} disabled={busy}>Cancel</Button>
            <Button variant="contained" disabled={busy || !valid} onClick={submit}>Create voucher</Button>
          </>
        )}
      </DialogActions>
    </Dialog>
  )
}

function ShareHistoryDialog({ id, typeCode, refNo, onClose }: { id: Id; typeCode: number; refNo: number; onClose: () => void }) {
  const [page, setPage] = useState(0)
  const size = 25
  const { data, error } = useQuery({ queryKey: ['member-share-history', id, typeCode, refNo, page], queryFn: () => getShareHistory(id.branchCode, id.clientId, typeCode, refNo, page, size) })
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>{data ? `${data.account.type ?? 'Share capital'} ${data.account.refNoText ?? ''}` : 'Share capital'}</DialogTitle>
      <DialogContent>
        {error && <Alert severity="error">{(error as Error).message}</Alert>}
        {data && (
          <Table size="small" aria-label="Share capital entries">
            <TableHead>
              <TableRow><TableCell>Date</TableCell><TableCell>Transaction</TableCell><TableCell>Document</TableCell><TableCell align="right">Amount</TableCell><TableCell align="right">Balance</TableCell></TableRow>
            </TableHead>
            <TableBody>
              {data.entries.map((e, i) => (
                <TableRow key={`${e.documentNo}-${i}`}>
                  <TableCell>{fmtDate(e.date) ?? '—'}</TableCell><TableCell>{e.transaction ?? `Code ${e.transactionCode}`}</TableCell><TableCell>{e.documentNo ?? '—'}</TableCell>
                  <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{peso(e.amount)}</TableCell>
                  <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{peso(e.balanceAfter)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DialogContent>
      <DialogActions>
        {data && <Box sx={{ flex: 1, pl: 2, fontSize: 13 }}>{data.totalEntries} entries</Box>}
        <Button disabled={page === 0} onClick={() => setPage(page - 1)}>Newer</Button>
        <Button disabled={!data || (page + 1) * size >= data.totalEntries} onClick={() => setPage(page + 1)}>Older</Button>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  )
}

function SavingsTab({ id }: { id: Id }) {
  const [open, setOpen] = useState<{ typeCode: number; refNo: number } | null>(null)
  const [cash, setCash] = useState<{ account: SavingsAccount; mode: 'deposit' | 'withdraw' } | null>(null)
  const { data, error, isLoading } = useQuery({ queryKey: ['member-savings', id], queryFn: () => getSavings(id.branchCode, id.clientId), staleTime: 60_000 })
  if (error) return <Alert severity="error">{(error as Error).message}</Alert>
  if (isLoading) return <Skeleton variant="rounded" height={160} />
  if (!data || data.accounts.length === 0) return <EmptyState icon={<FiInfo />} title="No savings" hint="This client has no savings accounts." />
  const num = { fontVariantNumeric: 'tabular-nums' } as const
  return (
    <>
      <TableCard>
        <Table size="small" aria-label="Savings accounts">
          <TableHead>
            <TableRow>
              <TableCell>Type</TableCell><TableCell>Account no.</TableCell><TableCell>Opened</TableCell><TableCell>Status</TableCell><TableCell>Last activity</TableCell>
              <TableCell align="right">Rate %</TableCell><TableCell align="right">Balance</TableCell><TableCell align="right">On hold</TableCell><TableCell align="right">Available</TableCell><TableCell />
            </TableRow>
          </TableHead>
          <TableBody>
            {data.accounts.map((a) => (
              <TableRow key={`${a.typeCode}-${a.refNo}`} hover sx={{ cursor: 'pointer' }} onClick={() => setOpen({ typeCode: a.typeCode, refNo: a.refNo })}>
                <TableCell>{a.type ?? `Type ${a.typeCode}`}</TableCell><TableCell>{a.refNoText ?? a.refNo}</TableCell>
                <TableCell>{fmtDate(a.opened) ?? '—'}</TableCell><TableCell>{a.statusName}</TableCell><TableCell>{fmtDate(a.lastActivity) ?? '—'}</TableCell>
                <TableCell align="right" sx={num}>{a.interestRate == null ? '—' : a.interestRate.toFixed(2)}</TableCell>
                <TableCell align="right" sx={num}>{peso(a.balance)}</TableCell>
                <TableCell align="right" sx={num}>{a.held ? peso(a.held) : '—'}</TableCell>
                <TableCell align="right" sx={num}>{peso(a.available)}</TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                  {a.status === 15 && (
                    <>
                      <Button size="small" onClick={(e) => { e.stopPropagation(); setCash({ account: a, mode: 'deposit' }) }}>Deposit</Button>
                      <Button size="small" onClick={(e) => { e.stopPropagation(); setCash({ account: a, mode: 'withdraw' }) }}>Withdraw</Button>
                    </>
                  )}
                </TableCell>
              </TableRow>
            ))}
            <TableRow>
              <TableCell colSpan={6} sx={{ fontWeight: 700 }}>Total savings</TableCell>
              <TableCell align="right" sx={{ fontWeight: 700, ...num }}>{peso(data.totalBalance)}</TableCell>
              <TableCell align="right" sx={{ fontWeight: 700, ...num }}>{data.totalHeld ? peso(data.totalHeld) : '—'}</TableCell>
              <TableCell />
              <TableCell />
            </TableRow>
          </TableBody>
        </Table>
      </TableCard>
      {open && <SavingsHistoryDialog id={id} typeCode={open.typeCode} refNo={open.refNo} onClose={() => setOpen(null)} />}
      {cash && <SavingsCashDialog id={id} account={cash.account} mode={cash.mode} onClose={() => setCash(null)} />}
    </>
  )
}

function SavingsCashDialog({ id, account, mode, onClose }: { id: Id; account: SavingsAccount; mode: 'deposit' | 'withdraw'; onClose: () => void }) {
  const qc = useQueryClient()
  const withdraw = mode === 'withdraw'
  const [amount, setAmount] = useState('')
  const [receiptNo, setReceiptNo] = useState('')
  const [explanation, setExplanation] = useState('')
  const [receipt, setReceipt] = useState<SavingsReceipt | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [askConfirm, setAskConfirm] = useState(false)
  const [check, setCheck] = useState<SavingsWithdrawalCheck | null>(null)
  const value = Number(amount)
  const valid = amount.trim() !== '' && Number.isFinite(value) && value > 0
  const body = { clientId: id.clientId, typeCode: account.typeCode, refNo: account.refNo, amount: value, explanation, receiptNo }

  // For a withdrawal, ask the server what stands in the way and what a supervisor has to allow; look again while a request waits.
  const waiting = !!check?.needs.some((n) => n.status === 'PENDING')
  useEffect(() => {
    if (!withdraw || !valid || receipt) { setCheck(null); return }
    let live = true
    const run = () => { checkSavingsWithdrawal(body).then((c) => { if (live) setCheck(c) }).catch((e: Error) => { if (live) setError(e.message) }) }
    const t = setTimeout(run, 400)
    const poll = waiting ? setInterval(run, 4000) : undefined
    return () => { live = false; clearTimeout(t); if (poll) clearInterval(poll) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [withdraw, valid, value, waiting, receipt])

  const ask = async () => {
    setBusy(true); setError(null)
    try {
      for (const n of check?.needs ?? []) {
        if (n.status === 'NONE' || n.status === 'REJECTED') await askFoOverride({ draftId: null, kind: n.kind, description: n.description, amount: n.amount, limitAmount: n.limit })
      }
      setCheck(await checkSavingsWithdrawal(body))
    } catch (e) { setError((e as Error).message) } finally { setBusy(false) }
  }

  const submit = async (confirmDuplicate: boolean) => {
    setBusy(true); setError(null)
    try {
      const r = await (withdraw ? postSavingsWithdrawal : postSavingsDeposit)({ ...body, confirmDuplicate })
      setReceipt(r)
      qc.invalidateQueries({ queryKey: ['member-savings'] })
      qc.invalidateQueries({ queryKey: ['member-savings-history'] })
    } catch (e) {
      const msg = (e as Error).message
      setError(msg)
      setAskConfirm(msg.includes('confirm it'))
    } finally { setBusy(false) }
  }

  const needsAsking = withdraw && !!check && check.needs.some((n) => n.status === 'NONE' || n.status === 'REJECTED')
  const blocked = withdraw && (!check || !check.ready)
  return (
    <Dialog open onClose={busy ? undefined : onClose} maxWidth="xs" fullWidth>
      <DialogTitle>{receipt ? (withdraw ? 'Withdrawal posted' : 'Deposit posted') : withdraw ? 'Withdraw cash' : 'Deposit cash'}</DialogTitle>
      <DialogContent>
        {!receipt && (
          <Box sx={{ display: 'grid', gap: 1.5, pt: 1 }}>
            <Box sx={{ fontSize: 13 }}>{account.type ?? 'Savings'} {account.refNoText ?? account.refNo} · balance {peso(account.balance)}{account.held ? ` · on hold ${peso(account.held)}` : ''} · available {peso(account.available)}</Box>
            <TextField label="Amount" type="number" autoFocus value={amount} onChange={(e) => { setAmount(e.target.value); setAskConfirm(false); setError(null) }} slotProps={{ htmlInput: { min: 0, step: '0.01' } }} />
            <TextField label="Receipt number (optional)" value={receiptNo} onChange={(e) => setReceiptNo(e.target.value)} helperText="Up to 8 letters or digits. Left empty, the system numbers it." />
            <TextField label="Explanation (optional)" value={explanation} onChange={(e) => setExplanation(e.target.value)} placeholder={withdraw ? 'SD WTH' : 'SD DEP'} />
            <Box sx={{ fontSize: 12 }}>{withdraw ? 'Cash is paid out of your own drawer and posted to the ledger at once.' : 'Cash goes into your own drawer and is posted to the ledger at once.'}</Box>
            {withdraw && check && check.problems.map((p) => <Alert key={p} severity="error">{p}</Alert>)}
            {withdraw && check && check.needs.map((n) => (
              <Alert key={n.kind} severity={n.status === 'APPROVED' ? 'success' : n.status === 'REJECTED' ? 'error' : 'warning'}>
                A supervisor has to allow this: {n.title}.{' '}
                <strong>{n.status === 'APPROVED' ? 'Allowed.' : n.status === 'PENDING' ? 'Waiting for a supervisor...' : n.status === 'REJECTED' ? 'Refused. You can ask again.' : 'Not asked yet.'}</strong>
              </Alert>
            ))}
          </Box>
        )}
        {receipt && (
          <Box sx={{ display: 'grid', gap: 0.5, pt: 1, fontSize: 14 }}>
            <Box>Receipt <strong>{receipt.documentNo}</strong> (control {receipt.controlNo}) dated {receipt.date}</Box>
            <Box>{receipt.clientName}</Box>
            <Box>{receipt.account}</Box>
            <Box>{withdraw ? 'Paid out' : 'Received'} <strong>{peso(receipt.amount)}</strong>, new balance <strong>{peso(receipt.balanceAfter)}</strong></Box>
            <Box>By {receipt.cashier}</Box>
          </Box>
        )}
        {error && <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert>}
      </DialogContent>
      <DialogActions>
        {receipt ? <Button onClick={onClose}>Close</Button> : (
          <>
            <Button onClick={onClose} disabled={busy}>Cancel</Button>
            {askConfirm
              ? <Button variant="contained" color="warning" disabled={busy} onClick={() => submit(true)}>Post anyway</Button>
              : needsAsking
                ? <Button variant="contained" disabled={busy || (check?.problems.length ?? 0) > 0} onClick={ask}>Ask a supervisor</Button>
                : <Button variant="contained" disabled={busy || !valid || blocked} onClick={() => submit(false)}>{withdraw ? 'Post withdrawal' : 'Post deposit'}</Button>}
          </>
        )}
      </DialogActions>
    </Dialog>
  )
}

function SavingsHistoryDialog({ id, typeCode, refNo, onClose }: { id: Id; typeCode: number; refNo: number; onClose: () => void }) {
  const [page, setPage] = useState(0)
  const size = 25
  const { data, error } = useQuery({ queryKey: ['member-savings-history', id, typeCode, refNo, page], queryFn: () => getSavingsHistory(id.branchCode, id.clientId, typeCode, refNo, page, size) })
  const num = { fontVariantNumeric: 'tabular-nums' } as const
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>{data ? `${data.account.type ?? 'Savings'} ${data.account.refNoText ?? ''}` : 'Savings'}</DialogTitle>
      <DialogContent>
        {error && <Alert severity="error">{(error as Error).message}</Alert>}
        {data && data.holds.length > 0 && (
          <Alert severity="warning" sx={{ mb: 2 }}>
            {data.holds.map((h, i) => (
              <Box key={i}>{h.reason ?? 'Hold'}: {peso(h.amount)}{h.description ? ` — ${h.description}` : ''}{h.date ? ` (${fmtDate(h.date)})` : ''}</Box>
            ))}
          </Alert>
        )}
        {data && (
          <Table size="small" aria-label="Savings entries">
            <TableHead>
              <TableRow><TableCell>Date</TableCell><TableCell>Transaction</TableCell><TableCell>Document</TableCell><TableCell align="right">Amount</TableCell><TableCell align="right">Balance</TableCell></TableRow>
            </TableHead>
            <TableBody>
              {data.entries.map((e, i) => (
                <TableRow key={`${e.documentNo}-${i}`}>
                  <TableCell>{fmtDate(e.date) ?? '—'}</TableCell><TableCell>{e.transaction ?? `Code ${e.transactionCode}`}</TableCell><TableCell>{e.documentNo ?? '—'}</TableCell>
                  <TableCell align="right" sx={{ color: e.amount < 0 ? 'error.main' : undefined, ...num }}>{peso(e.amount)}</TableCell>
                  <TableCell align="right" sx={num}>{peso(e.balanceAfter)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DialogContent>
      <DialogActions>
        {data && <Box sx={{ flex: 1, pl: 2, fontSize: 13 }}>{data.totalEntries} entries</Box>}
        <Button disabled={page === 0} onClick={() => setPage(page - 1)}>Newer</Button>
        <Button disabled={!data || (page + 1) * size >= data.totalEntries} onClick={() => setPage(page + 1)}>Older</Button>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  )
}

function TabContent({ tab, m, lk }: { tab: number; m: Member; lk?: Lookups }) {
  const key = TAB_KEYS[tab]
  if (key === 'personal') return <PersonalTab m={m} lk={lk} />
  if (key === 'history') return <HistoryTab id={m.id} />
  if (key === 'billing') return <BillingTab id={m.id} />
  if (key === 'shares') return <ShareCapitalTab id={m.id} />
  if (key === 'savings') return <SavingsTab id={m.id} />
  if (key === 'loans') return <LoansTab branchCode={m.id.branchCode} clientId={m.id.clientId} />
  if (key === 'sketch') return <EmptyState icon={<FiInfo />} title="No sketch" hint="Residence sketches are not stored in the database yet." />
  return <DetailsTab id={m.id} tab={key} title={TABS[tab]} lk={lk} />
}

function Profile({ id, lk }: { id: Id; lk?: Lookups }) {
  const [tab, setTab] = useState(0)
  const { data: m, error, isLoading } = useQuery({
    queryKey: ['member', id],
    queryFn: () => getMember(id.branchCode, id.clientId),
  })

  if (error) return <Alert severity="error" sx={{ m: 2 }}>{(error as Error).message}</Alert>
  if (isLoading || !m) {
    return (
      <Box sx={(t) => ({ p: 2.5, ...glass(t) })}>
        <Box sx={{ display: 'flex', gap: 2.5, mb: 3 }}>
          <Skeleton variant="circular" width={72} height={72} />
          <Box sx={{ flex: 1 }}><Skeleton width="50%" height={36} /><Skeleton width="30%" /></Box>
        </Box>
        <Skeleton variant="rounded" height={220} />
      </Box>
    )
  }
  return (
    <>
      <ProfileHeader m={m} lk={lk} />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} variant="scrollable" scrollButtons="auto" aria-label="Profile sections"
        sx={(t) => ({
          mt: 1, px: 1, minHeight: 40, ...glass(t),
          '& .MuiTab-root': { minHeight: 40, fontSize: 13.5, px: 1.5 },
          '& .MuiTabs-indicator': { height: 3, borderRadius: '3px 3px 0 0' },
        })}>
        {TABS.map((t) => <Tab key={t} label={t} />)}
      </Tabs>
      <Box sx={{ pt: 1, flex: 1, overflow: 'auto' }}>
        <TabContent tab={tab} m={m} lk={lk} />
      </Box>
    </>
  )
}

/** Re-reads everything a decision on a change can alter. */
const refreshAfterDecision = (qc: ReturnType<typeof useQueryClient>, id: Id) =>
  Promise.all(['member', 'member-details', 'pending-change'].map((k) => qc.invalidateQueries({ queryKey: [k, id] })).concat([
    qc.invalidateQueries({ queryKey: ['members'] }),
    qc.invalidateQueries({ queryKey: ['pending-count'] }),
    qc.invalidateQueries({ queryKey: ['changes'] }),
  ]))

/** A change is waiting for approval: shows what is proposed, and offers the actions this person is allowed. */
function PendingBanner({ change, id, canApprove, onDone }: { change: Change; id: Id; canApprove: boolean; onDone: (message: string) => void }) {
  const qc = useQueryClient()
  const me = useMe()
  const mine = change.requestedBy.toLowerCase() === me.username.toLowerCase()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [rejecting, setRejecting] = useState(false)
  const [note, setNote] = useState('')

  const run = async (action: () => Promise<unknown>, done: string) => {
    setBusy(true)
    setError(null)
    try {
      await action()
      await refreshAfterDecision(qc, id)
      setRejecting(false)
      onDone(done)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Box sx={(t) => ({ mb: 1.5, p: 2, ...glass(t), borderColor: 'warning.main' })} role="region" aria-label="Change waiting for approval">
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
        <FiClock aria-hidden />
        <Typography sx={{ fontWeight: 600, fontSize: 14 }}>Waiting for approval</Typography>
      </Box>
      <Typography sx={{ fontSize: 13, mb: 1.25 }} color="text.secondary">
        {mine ? 'You' : change.requestedByName} asked for {change.items.length} change{change.items.length === 1 ? '' : 's'} on {when(change.requestedAt)}.
        {' '}{mine ? 'A Membership Officer who is not you has to approve it.' : canApprove ? '' : 'A Membership Officer has to approve it.'}
      </Typography>
      <Table size="small" aria-label="Proposed changes" sx={{ mb: 1.25 }}>
        <TableHead><TableRow><TableCell>Field</TableCell><TableCell>Now</TableCell><TableCell>Proposed</TableCell></TableRow></TableHead>
        <TableBody>
          {change.items.map((i) => (
            <TableRow key={i.column}>
              <TableCell>{i.label}</TableCell>
              <TableCell sx={{ color: 'text.secondary' }}>{i.fromText ?? '—'}</TableCell>
              <TableCell sx={{ fontWeight: 600 }}>{i.toText ?? '—'}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      {error && <Alert severity="error" sx={{ mb: 1 }}>{error}</Alert>}
      <Box sx={{ display: 'flex', gap: 1, justifyContent: 'flex-end' }}>
        {mine && <Button size="small" disabled={busy} onClick={() => run(() => withdrawChange(change.id), 'Change withdrawn')}>Withdraw</Button>}
        {canApprove && !mine && (
          <>
            <Button size="small" color="error" disabled={busy} onClick={() => setRejecting(true)}>Reject</Button>
            <Button size="small" variant="contained" disabled={busy} startIcon={<FiCheckCircle />} onClick={() => run(() => approveChange(change.id), 'Change approved and saved')}>
              Approve
            </Button>
          </>
        )}
      </Box>

      <Dialog open={rejecting} onClose={() => setRejecting(false)} fullWidth maxWidth="xs" aria-labelledby="reject-title">
        <DialogTitle id="reject-title">Reject this change?</DialogTitle>
        <DialogContent>
          <DialogContentText sx={{ mb: 1.5 }}>Tell {change.requestedByName} why, so they can correct it.</DialogContentText>
          <TextField label="Reason" value={note} onChange={(e) => setNote(e.target.value)} fullWidth autoFocus multiline minRows={2} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRejecting(false)}>Keep it</Button>
          <Button color="error" disabled={busy || note.trim().length < 3} onClick={() => run(() => rejectChange(change.id, note.trim()), 'Change rejected')}>Reject</Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}

/** Shown while a profile is being edited: what is pending, and the way to save or leave. */
function EditBar({ changes, saving, error, onSave, onCancel }: {
  changes: number; saving: boolean; error: string | null; onSave: () => void; onCancel: () => void
}) {
  return (
    <Box sx={{ mb: 1.5 }}>
      <Box sx={(t) => ({ display: 'flex', alignItems: 'center', gap: 1.5, px: 2, py: 1, ...glass(t) })}>
        <FiEdit2 aria-hidden />
        <Typography sx={{ flex: 1, fontSize: 13.5 }} role="status">
          Editing. {changes === 0 ? 'No changes yet.' : `${changes} change${changes === 1 ? '' : 's'} ready.`} Saving sends them to a Membership Officer for approval.
        </Typography>
        <Button size="small" onClick={onCancel} disabled={saving}>Cancel</Button>
        <Button size="small" variant="contained" startIcon={<FiSave />} onClick={onSave} disabled={saving || changes === 0}>
          {saving ? 'Sending…' : 'Send for approval'}
        </Button>
      </Box>
      {error && <Alert severity="error" sx={{ mt: 1 }}>{error}</Alert>}
    </Box>
  )
}

// ---- page ---------------------------------------------------------------------------------------------------------

export default function ClientProfilePage({ selected, onSelect }: { selected: Id | null; onSelect: (id: Id) => void }) {
  const qc = useQueryClient()
  const [finding, setFinding] = useState(false)
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [discarding, setDiscarding] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)

  const { data: lk } = useQuery({ queryKey: ['lookups'], queryFn: getLookups, staleTime: Infinity })
  const { data: caps } = useQuery({ queryKey: ['capabilities'], queryFn: getCapabilities, staleTime: 60_000 })
  const { data: member } = useQuery({
    queryKey: ['member', selected],
    queryFn: () => getMember(selected!.branchCode, selected!.clientId),
    enabled: !!selected,
  })
  const { data: pending } = useQuery({
    queryKey: ['pending-change', selected],
    queryFn: () => getPendingChange(selected!.branchCode, selected!.clientId),
    enabled: !!selected,
  })
  const changes = Object.keys(draft).length
  const key = selected ? `${selected.branchCode}-${selected.clientId}` : ''

  // Opening another client ends any edit in progress. (State is reset while rendering, not in an effect, so the
  // new client never flashes with the old client's unsaved values.)
  const [shownKey, setShownKey] = useState(key)
  if (key !== shownKey) {
    setShownKey(key)
    setEditing(false)
    setDraft({})
    setError(null)
  }

  const set = (col: string, value: string, original: string) =>
    setDraft((d) => {
      const next = { ...d }
      if (value === original) delete next[col]
      else next[col] = value
      return next
    })
  const leave = () => { setEditing(false); setDraft({}); setError(null); setDiscarding(false) }
  const cancel = () => (changes > 0 ? setDiscarding(true) : leave())

  const save = async () => {
    if (!selected || !member || changes === 0) return
    setSaving(true)
    setError(null)
    try {
      await submitChange(selected.branchCode, selected.clientId, { changes: draft, expectedUpdatedAt: member.updatedAt })
      await Promise.all([
        qc.invalidateQueries({ queryKey: ['pending-change', selected] }),
        qc.invalidateQueries({ queryKey: ['pending-count'] }),
        qc.invalidateQueries({ queryKey: ['changes'] }),
      ])
      leave()
      setNotice('Sent for approval')
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setSaving(false)
    }
  }

  // With no client open the finder is already on the page, so Find just focuses it. With a client open, Find
  // (the toolbar item or Ctrl/Cmd+F) opens the pop-up so the profile stays visible behind it.
  const find = () => {
    if (selected) setFinding(true)
    else document.getElementById('find-client-input')?.focus()
  }

  const runs: Record<string, (() => void) | undefined> = {
    Edit: caps?.edit && selected && member && !pending && !editing ? () => { setEditing(true); setError(null) } : undefined,
    Save: editing && changes > 0 && !saving ? save : undefined,
    Cancel: editing && !saving ? cancel : undefined,
  }
  const actions = ACTIONS.map((a) => ({ ...a, run: runs[a.label] }))

  // Keyboard: Ctrl/Cmd+F runs Find; the other shortcuts run their action while it is possible.
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      if (mod(e) && e.key === 'f') {
        e.preventDefault()
        find()
        return
      }
      if (finding || discarding) return
      const action = actions.find((a) => a.match(e))
      if (action?.run) { e.preventDefault(); action.run() }
    }
    window.addEventListener('keydown', on)
    return () => window.removeEventListener('keydown', on)
  })

  return (
    <Box sx={(t) => ({ display: 'flex', flexDirection: 'column', height: 'calc(100dvh - 44px)', background: appBackground(t) })}>
      {/* The title-bar menu carries Find Client plus the record actions. */}
      <ToolbarPortal><ClientMenu onFind={find} actions={actions} editOn={!!caps?.edit} pendingNote={pending ? 'A change is waiting for approval.' : null} /></ToolbarPortal>
      <FindClientDialog open={finding} onClose={() => setFinding(false)} lk={lk} onPick={(id) => { setFinding(false); onSelect(id) }} />

      <Dialog open={discarding} onClose={() => setDiscarding(false)} aria-labelledby="discard-title">
        <DialogTitle id="discard-title">Discard changes?</DialogTitle>
        <DialogContent>
          <DialogContentText>{changes} change{changes === 1 ? '' : 's'} will be lost.</DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDiscarding(false)} autoFocus>Keep editing</Button>
          <Button onClick={leave} color="error">Discard</Button>
        </DialogActions>
      </Dialog>
      <Snackbar open={!!notice} autoHideDuration={3000} onClose={() => setNotice(null)} message={notice} />

      <Box component="section" aria-label="Client profile" sx={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0, minWidth: 0, maxWidth: 1600, width: '100%', mx: 'auto', p: 1.5 }}>
        {selected
          ? (
            <EditCtx.Provider value={{ editing, draft, set }}>
              {pending && <PendingBanner change={pending} id={selected} canApprove={!!caps?.approve} onDone={setNotice} />}
              {editing && <EditBar changes={changes} saving={saving} error={error} onSave={save} onCancel={cancel} />}
              <Profile key={key} id={selected} lk={lk} />
            </EditCtx.Provider>
          )
          : (
            // First opening: the search is ready with the cursor in it, so there is nothing to click first.
            <Box sx={{ flex: 1, display: 'flex', justifyContent: 'center', alignItems: 'flex-start', pt: '10vh' }}>
              <Box sx={(t) => ({ width: '100%', maxWidth: 580, overflow: 'hidden', ...glass(t, { elevated: true }) })}>
                <FindBody inline lk={lk} onPick={onSelect} />
              </Box>
            </Box>
          )}
      </Box>
    </Box>
  )
}
