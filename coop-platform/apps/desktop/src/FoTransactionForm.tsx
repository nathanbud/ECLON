import {
  Autocomplete, Box, Button, IconButton, MenuItem, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, TextField, Tooltip, Typography,
} from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { FiPlus, FiTrash2 } from 'react-icons/fi'
import { getFoClientAccounts, searchFoAccounts, searchMembers, type FoAllocation, type FoCash, type FoClientAccount, type FoCheque, type FoSetup, type FoTypeInfo } from './api'
import { displayName, memberNo } from './clients'
import { glass } from './glass'

// ---- the form's data ----------------------------------------------------------------------------------------------

export interface FoLineState { key: string; accountCode: string; accountDesc: string; acctNum: string; debit: string; credit: string; dept: number | null; slc?: number | null; slt?: number | null }

export interface FoForm {
  trCode: number | null
  date: string
  batchNo: string
  dept: number | null
  clientId: number | null
  clientName: string
  refNo: string
  explanation: string
  lines: FoLineState[]
  denoms: Record<number, string>
}

/** What a posted transaction shows that a draft does not have yet. */
export interface FoExtras { ctl: string; time: string; cheques: FoCheque[]; allocations: FoAllocation[]; cash: FoCash[] }

let lineCounter = 0
export const newLine = (): FoLineState => ({ key: `l${++lineCounter}`, accountCode: '', accountDesc: '', acctNum: '', debit: '', credit: '', dept: null })

export const emptyForm = (trCode: number | null, date: string): FoForm => ({
  trCode, date, batchNo: date.replace(/-/g, ''), dept: null, clientId: null, clientName: '', refNo: '', explanation: '',
  lines: [newLine(), newLine()], denoms: {},
})

const num = (s: string) => {
  const n = Number(s.replace(/,/g, ''))
  return Number.isFinite(n) ? n : 0
}
export const formTotals = (lines: FoLineState[]) => {
  const debit = lines.reduce((a, l) => a + num(l.debit), 0)
  const credit = lines.reduce((a, l) => a + num(l.credit), 0)
  return { debit, credit, difference: Math.round((debit - credit) * 100) / 100 }
}
export const money = (n: number) => n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })

// ---- small inputs -------------------------------------------------------------------------------------------------

const box = { '& .MuiOutlinedInput-root': { fontSize: 14 }, '& .MuiInputLabel-root': { fontSize: 13 } } as const

/** A text input that is read-only when the form is not being edited. */
function Text({ label, value, onChange, editing, type, width, align, multiline }: {
  label: string; value: string; onChange?: (v: string) => void; editing: boolean; type?: string; width?: number | string; align?: 'right'; multiline?: boolean
}) {
  return (
    <TextField
      label={label} value={value} onChange={editing && onChange ? (e) => onChange(e.target.value) : undefined} type={editing ? type : undefined}
      placeholder="—" fullWidth={!width} multiline={multiline} minRows={multiline ? 3 : undefined}
      slotProps={{ input: { readOnly: !editing }, inputLabel: { shrink: true }, htmlInput: align ? { style: { textAlign: align } } : {} }}
      sx={{ ...box, width }}
    />
  )
}

function Amount({ value, onChange, label, onEnter }: { value: string; onChange: (v: string) => void; label: string; onEnter?: () => void }) {
  return (
    <TextField
      value={value} onChange={(e) => onChange(e.target.value.replace(/[^0-9.,]/g, ''))} size="small" placeholder="0.00"
      onKeyDown={(e) => { if (e.key === 'Enter' && onEnter) { e.preventDefault(); onEnter() } }}
      slotProps={{ htmlInput: { inputMode: 'decimal', 'aria-label': label, style: { textAlign: 'right' } } }} sx={{ ...box, width: 130 }}
    />
  )
}

const RECENT_KEY = 'coop.foRecentAccounts'
type Account = { code: string; description: string }
const readRecent = (): Account[] => {
  try { return JSON.parse(localStorage.getItem(RECENT_KEY) ?? '[]') } catch { return [] }
}
const remember = (a: Account) => {
  try { localStorage.setItem(RECENT_KEY, JSON.stringify([a, ...readRecent().filter((x) => x.code !== a.code)].slice(0, 8))) } catch { /* storage unavailable */ }
}

/** Searches the chart of accounts as you type. With nothing typed it offers the accounts you used last. */
function AccountPicker({ code, desc, onPick, label, autoFocus }: { code: string; desc: string; onPick: (a: Account | null) => void; label: string; autoFocus?: boolean }) {
  const [input, setInput] = useState('')
  const [q, setQ] = useState('')
  useEffect(() => {
    const t = setTimeout(() => setQ(input.trim()), 200)
    return () => clearTimeout(t)
  }, [input])
  const { data } = useQuery({ queryKey: ['fo-accounts', q], queryFn: () => searchFoAccounts(q), enabled: q.length >= 1, staleTime: 60_000 })
  const value = code ? { code, description: desc } : null
  const options = q.length >= 1 ? data ?? [] : readRecent()
  return (
    <Autocomplete
      size="small" options={options} value={value} filterOptions={(o) => o} autoHighlight openOnFocus
      getOptionLabel={(o) => o.code} isOptionEqualToValue={(a, b) => a.code === b.code}
      groupBy={() => (q.length >= 1 ? 'Accounts' : 'Recently used')}
      onChange={(_, v) => { if (v) remember(v); onPick(v) }} onInputChange={(_, v, reason) => { if (reason === 'input') setInput(v) }}
      noOptionsText="Type an account code or name"
      renderOption={(props, o) => {
        const { key, ...rest } = props as typeof props & { key: string }
        return (
          <Box component="li" key={key} {...rest} sx={{ display: 'flex', gap: 1.5 }}>
            <Typography sx={{ fontSize: 13.5, fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>{o.code}</Typography>
            <Typography noWrap sx={{ fontSize: 13 }} color="text.secondary">{o.description}</Typography>
          </Box>
        )
      }}
      renderInput={(params) => <TextField {...params} autoFocus={autoFocus} placeholder="Account" slotProps={{ ...params.slotProps, htmlInput: { ...params.slotProps.htmlInput, 'aria-label': label } }} sx={box} />}
      sx={{ width: 150 }} slotProps={{ popper: { style: { minWidth: 320 } } }}
    />
  )
}

/** Finds a client by name or member ID. */
function ClientPicker({ id, name, onPick }: { id: number | null; name: string; onPick: (c: { id: number; name: string } | null) => void }) {
  const [input, setInput] = useState('')
  const [q, setQ] = useState('')
  useEffect(() => {
    const t = setTimeout(() => setQ(input.trim()), 250)
    return () => clearTimeout(t)
  }, [input])
  const { data } = useQuery({ queryKey: ['members', q, 'fo'], queryFn: () => searchMembers(q, 0, 8), enabled: q.length >= 2, staleTime: 30_000 })
  const value = id != null ? { id, name } : null
  return (
    <Autocomplete
      size="small" options={(data?.content ?? []).map((m) => ({ id: m.id.clientId, name: displayName(m), no: memberNo(m.id) }))}
      value={value} filterOptions={(o) => o} autoHighlight fullWidth
      getOptionLabel={(o) => o.name} isOptionEqualToValue={(a, b) => a.id === b.id}
      onChange={(_, v) => onPick(v ? { id: v.id, name: v.name } : null)} onInputChange={(_, v, reason) => { if (reason === 'input') setInput(v) }}
      noOptionsText="Type at least 2 letters of the name, or a member ID"
      renderInput={(params) => <TextField {...params} label="Client" slotProps={{ ...params.slotProps, inputLabel: { shrink: true } }} placeholder="Name or member ID" sx={box} />}
    />
  )
}

/**
 * The usual case in one step: pick the member's account, type the amount, press Enter. The two entries are built for you:
 * the member's account on one side and cash on the other.
 */
function QuickEntry({ clientId, side, onAdd, cashName }: { clientId: number | null; side: 'credit' | 'debit'; onAdd: (a: FoClientAccount, amount: string) => void; cashName: string }) {
  const { data, isFetching } = useQuery({ queryKey: ['fo-client-accounts', clientId], queryFn: () => getFoClientAccounts(clientId!), enabled: clientId != null, staleTime: 60_000 })
  const [pick, setPick] = useState('')
  const [amount, setAmount] = useState('')
  const accounts = (data ?? []).filter((a) => a.glCode)
  // Savings first: it is by far the commonest account to pay into or take from.
  const chosen = accounts.find((a) => `${a.slc}-${a.slt}-${a.refNo}` === pick) ?? accounts.find((a) => a.kind === 'Savings Deposit') ?? accounts[0]
  const ready = !!chosen && num(amount) > 0
  const add = () => {
    if (!chosen || !ready) return
    onAdd(chosen, num(amount).toFixed(2))
    setAmount('')
  }
  return (
    <Card title="Quick entry">
      {clientId == null
        ? <Typography sx={{ fontSize: 13 }} color="text.secondary">Pick the client above, then choose the account and amount.</Typography>
        : (
          <Box sx={{ display: 'grid', gap: 1.25 }}>
            <TextField
              select label="Member's account" size="small" fullWidth value={chosen ? `${chosen.slc}-${chosen.slt}-${chosen.refNo}` : ''} onChange={(e) => setPick(e.target.value)}
              disabled={isFetching || accounts.length === 0} sx={box} slotProps={{ inputLabel: { shrink: true } }}
              helperText={!isFetching && accounts.length === 0 ? 'This member has no accounts that can be posted to.' : ' '}
            >
              {accounts.map((a) => <MenuItem key={`${a.slc}-${a.slt}-${a.refNo}`} value={`${a.slc}-${a.slt}-${a.refNo}`}>{a.type}{a.number ? ` · ${a.number}` : ` · ${a.refNo}`}</MenuItem>)}
            </TextField>
            <Box sx={{ display: 'flex', gap: 1, alignItems: 'flex-start' }}>
              <TextField
                label="Amount" size="small" fullWidth value={amount} onChange={(e) => setAmount(e.target.value.replace(/[^0-9.,]/g, ''))}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add() } }}
                slotProps={{ inputLabel: { shrink: true }, htmlInput: { inputMode: 'decimal', style: { textAlign: 'right' } } }} sx={box} placeholder="0.00"
              />
              <Button variant="contained" size="small" disabled={!ready} onClick={add} sx={{ whiteSpace: 'nowrap', height: 40 }}>Add entries</Button>
            </Box>
            {chosen && (
              <Typography sx={{ fontSize: 12 }} color="text.secondary">
                {side === 'credit' ? `Credits ${chosen.glDesc ?? chosen.glCode}, debits ${cashName}.` : `Debits ${chosen.glDesc ?? chosen.glCode}, credits ${cashName}.`}
              </Typography>
            )}
          </Box>
        )}
    </Card>
  )
}

function Card({ title, children, sx }: { title?: string; children: React.ReactNode; sx?: object }) {
  return (
    <Box sx={(t) => ({ p: 1.5, ...glass(t), ...sx })}>
      {title && <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 1 }} color="text.secondary">{title}</Typography>}
      {children}
    </Box>
  )
}

// ---- the form -----------------------------------------------------------------------------------------------------

export default function FoTransactionForm({ setup, types, form, extras, editing, onChange, departments, focusKey, onFocusLine, errorRow, onBalance, cashBalance, quickSide, onQuickAdd }: {
  setup: FoSetup
  types: FoTypeInfo[]
  form: FoForm
  extras: FoExtras
  editing: boolean
  onChange: (patch: Partial<FoForm>) => void
  departments: [string, string][]
  /** The entry that should take the cursor (a row just added), and a way to say a row was added. */
  focusKey?: string | null
  onFocusLine?: (key: string) => void
  /** The entry number (from 1) the last save complained about. */
  errorRow?: number | null
  /** Adds an entry that makes debits equal credits; with `cash`, using the cash account. */
  onBalance?: (cash: boolean) => void
  /** Whether this kind of transaction normally goes through the cash drawer. */
  cashBalance?: boolean
  /** Which side the member's account is on for quick entry (credit for a receipt, debit for a payout), or null when it does not apply. */
  quickSide?: 'credit' | 'debit' | null
  onQuickAdd?: (a: FoClientAccount, amount: string) => void
}) {
  const [panel, setPanel] = useState(0)
  const totals = formTotals(form.lines)
  const setLine = (key: string, patch: Partial<FoLineState>) => onChange({ lines: form.lines.map((l) => (l.key === key ? { ...l, ...patch } : l)) })
  const deptName = (d: number | null) => (d == null ? '' : departments.find(([k]) => Number(k) === d)?.[1] ?? String(d))
  const cashTotal = setup.denominations.reduce((a, d) => a + d.amount * num(form.denoms[d.id] ?? ''), 0)
  const balanced = Math.abs(totals.difference) < 0.005
  const addLine = () => {
    const l = newLine()
    onChange({ lines: [...form.lines, l] })
    onFocusLine?.(l.key)
  }

  return (
    <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: 'minmax(300px, 360px) minmax(0, 1fr)', flex: 1, minHeight: 0 }}>
      {/* Left: the transaction, where the money went, and why */}
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5, minHeight: 0, overflow: 'auto', pr: 0.25 }}>
        <Card>
          <Box sx={{ display: 'grid', gap: '12px 10px', gridTemplateColumns: '1fr 1fr' }}>
            <Box sx={{ gridColumn: '1 / -1' }}>
              <TextField
                select label="Trans. type" value={form.trCode ?? ''} fullWidth
                onChange={editing ? (e) => onChange({ trCode: Number(e.target.value) }) : undefined}
                slotProps={{ input: { readOnly: !editing || types.length < 2 }, inputLabel: { shrink: true } }} sx={box}
              >
                {types.map((t) => <MenuItem key={t.id} value={t.id} disabled={editing && !t.canNew}>{t.desc}</MenuItem>)}
              </TextField>
            </Box>
            <Text label="Control no." value={extras.ctl} editing={false} />
            <TextField
              select label="Dept" value={form.dept ?? ''} fullWidth onChange={editing ? (e) => onChange({ dept: e.target.value === '' ? null : Number(e.target.value) }) : undefined}
              slotProps={{ input: { readOnly: !editing }, inputLabel: { shrink: true }, select: { displayEmpty: true } }} sx={box}
            >
              <MenuItem value=""><em>—</em></MenuItem>
              {departments.map(([k, name]) => <MenuItem key={k} value={Number(k)}>{name}</MenuItem>)}
            </TextField>
            <Box sx={{ gridColumn: '1 / -1' }}>
              {editing
                ? <ClientPicker id={form.clientId} name={form.clientName} onPick={(c) => onChange({ clientId: c?.id ?? null, clientName: c?.name ?? '' })} />
                : <Text label="Client" value={form.clientId != null ? `${form.clientName || ''}  (${form.clientId})` : form.clientName} editing={false} />}
            </Box>
            <Text label="Ref. number" value={form.refNo} onChange={(v) => onChange({ refNo: v })} editing={editing} />
            <Text label="Trans. date" value={form.date} onChange={(v) => onChange({ date: v })} editing={editing} type="date" />
            <Text label="Batch no." value={form.batchNo} onChange={(v) => onChange({ batchNo: v })} editing={editing} />
            <Text label="Time" value={extras.time} editing={false} />
          </Box>
        </Card>

        {editing && quickSide && onQuickAdd && <QuickEntry clientId={form.clientId} side={quickSide} onAdd={onQuickAdd} cashName={setup.cashAccountDesc} />}

        <Card title="Summary of accounts">
          <Box sx={{ display: 'grid', gap: 0.5, gridTemplateColumns: '1fr auto' }}>
            {['Loan Accounts', 'A/R Accounts', 'Pledges', 'Share Capital', 'Savings Deposit', 'Time Deposit', 'Other Accounts'].map((label) => {
              const a = extras.allocations.find((x) => x.label === label)?.amount ?? 0
              return (
                <Box key={label} sx={{ display: 'contents' }}>
                  <Typography sx={{ fontSize: 13.5 }}>{label}</Typography>
                  <Typography sx={{ fontSize: 13.5, fontVariantNumeric: 'tabular-nums', textAlign: 'right', opacity: a ? 1 : 0.4 }}>{a ? money(a) : '—'}</Typography>
                </Box>
              )
            })}
          </Box>
          {editing && <Typography sx={{ fontSize: 12, mt: 1 }} color="text.secondary">Filled in once the transaction is posted.</Typography>}
        </Card>

        <Card title="Explanation" sx={{ flex: 1, minHeight: 130 }}>
          <Text label="" value={form.explanation} onChange={(v) => onChange({ explanation: v })} editing={editing} multiline />
        </Card>
      </Box>

      {/* Right: the entries */}
      <Box sx={(t) => ({ display: 'flex', flexDirection: 'column', minHeight: 0, minWidth: 0, ...glass(t) })}>
        <Box sx={{ display: 'flex', alignItems: 'center', borderBottom: 1, borderColor: 'divider' }}>
          <Tabs value={panel} onChange={(_, v) => setPanel(v)} aria-label="Transaction details" sx={{ flex: 1, minHeight: 40, '& .MuiTab-root': { minHeight: 40, fontSize: 13.5 } }}>
            <Tab label="GL/SL Entries" /><Tab label={`COCI Details${extras.cheques.length ? ` (${extras.cheques.length})` : ''}`} /><Tab label="Cash Denomination" />
          </Tabs>
          <Box sx={{ px: 2, textAlign: 'right' }}>
            <Typography sx={{ fontSize: 11 }} color="text.secondary">Total</Typography>
            <Typography sx={{ fontSize: 18, fontWeight: 700, fontVariantNumeric: 'tabular-nums', lineHeight: 1.1 }}>{money(totals.debit)}</Typography>
          </Box>
        </Box>

        <Box sx={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
          {panel === 0 && (
            <Table size="small" stickyHeader aria-label="GL and SL entries">
              <TableHead>
                <TableRow>
                  <TableCell sx={{ width: 36 }}>#</TableCell><TableCell>Acct description</TableCell><TableCell>G/L acct code</TableCell>
                  <TableCell>Acct num</TableCell><TableCell align="right">Debit</TableCell><TableCell align="right">Credit</TableCell><TableCell>Dept</TableCell>
                  {editing && <TableCell sx={{ width: 40 }} />}
                </TableRow>
              </TableHead>
              <TableBody>
                {form.lines.map((l, i) => (
                  <TableRow key={l.key} hover sx={errorRow === i + 1 ? { outline: '2px solid', outlineColor: 'error.main', outlineOffset: -2 } : undefined} aria-invalid={errorRow === i + 1 || undefined}>
                    <TableCell>{i + 1}</TableCell>
                    <TableCell sx={{ minWidth: 160 }}>{l.accountDesc || (editing ? '' : '—')}</TableCell>
                    <TableCell>
                      {editing
                        ? <AccountPicker code={l.accountCode} desc={l.accountDesc} label={`Entry ${i + 1} account`} autoFocus={l.key === focusKey} onPick={(a) => setLine(l.key, { accountCode: a?.code ?? '', accountDesc: a?.description ?? '' })} />
                        : l.accountCode}
                    </TableCell>
                    <TableCell>
                      {editing
                        ? <TextField size="small" value={l.acctNum} onChange={(e) => setLine(l.key, { acctNum: e.target.value })} slotProps={{ htmlInput: { 'aria-label': `Entry ${i + 1} account number` } }} sx={{ ...box, width: 90 }} />
                        : l.acctNum}
                    </TableCell>
                    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                      {editing ? <Amount value={l.debit} label={`Entry ${i + 1} debit`} onEnter={i === form.lines.length - 1 ? addLine : undefined} onChange={(v) => setLine(l.key, { debit: v, credit: num(v) ? '' : l.credit })} /> : num(l.debit) ? money(num(l.debit)) : ''}
                    </TableCell>
                    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                      {editing ? <Amount value={l.credit} label={`Entry ${i + 1} credit`} onEnter={i === form.lines.length - 1 ? addLine : undefined} onChange={(v) => setLine(l.key, { credit: v, debit: num(v) ? '' : l.debit })} /> : num(l.credit) ? money(num(l.credit)) : ''}
                    </TableCell>
                    <TableCell>
                      {editing
                        ? (
                          <TextField select size="small" value={l.dept ?? ''} onChange={(e) => setLine(l.key, { dept: e.target.value === '' ? null : Number(e.target.value) })} sx={{ ...box, width: 150 }}
                            slotProps={{ htmlInput: { 'aria-label': `Entry ${i + 1} department` }, select: { displayEmpty: true } }}>
                            <MenuItem value=""><em>—</em></MenuItem>
                            {departments.map(([k, name]) => <MenuItem key={k} value={Number(k)}>{name}</MenuItem>)}
                          </TextField>
                        )
                        : deptName(l.dept)}
                    </TableCell>
                    {editing && (
                      <TableCell>
                        <Tooltip title="Remove entry"><IconButton size="small" aria-label={`Remove entry ${i + 1}`} onClick={() => onChange({ lines: form.lines.length > 1 ? form.lines.filter((x) => x.key !== l.key) : [newLine()] })}><FiTrash2 /></IconButton></Tooltip>
                      </TableCell>
                    )}
                  </TableRow>
                ))}
                {editing && (
                  <TableRow>
                    <TableCell colSpan={8}>
                      <Tooltip title="Add another entry"><IconButton size="small" aria-label="Add entry" onClick={addLine}><FiPlus /></IconButton></Tooltip>
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          )}

          {panel === 1 && (
            extras.cheques.length === 0
              ? <Typography sx={{ p: 2.5, fontSize: 13.5 }} color="text.secondary">{editing ? 'Cheque details are not part of drafts yet.' : 'This transaction has no cheques.'}</Typography>
              : (
                <Table size="small" stickyHeader aria-label="Cheques">
                  <TableHead><TableRow><TableCell>#</TableCell><TableCell>Type</TableCell><TableCell>Cheque no.</TableCell><TableCell>Bank</TableCell><TableCell>Cheque date</TableCell><TableCell>Payee</TableCell><TableCell align="right">Amount</TableCell><TableCell>Cleared</TableCell></TableRow></TableHead>
                  <TableBody>
                    {extras.cheques.map((c) => (
                      <TableRow key={c.seq} hover>
                        <TableCell>{c.seq + 1}</TableCell><TableCell>{c.type}</TableCell><TableCell>{c.checkNo ?? '—'}</TableCell><TableCell>{c.bank ?? '—'}</TableCell>
                        <TableCell>{c.checkDate ?? '—'}</TableCell><TableCell>{c.payee ?? '—'}</TableCell>
                        <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{money(c.amount)}</TableCell><TableCell>{c.cleared ? 'Yes' : 'No'}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )
          )}

          {panel === 2 && (
            editing
              ? (
                <Table size="small" stickyHeader aria-label="Cash denominations">
                  <TableHead><TableRow><TableCell>Denomination</TableCell><TableCell align="right" sx={{ width: 140 }}>Count</TableCell><TableCell align="right">Amount</TableCell></TableRow></TableHead>
                  <TableBody>
                    {setup.denominations.map((d) => (
                      <TableRow key={d.id} hover>
                        <TableCell>{d.kind} {d.description}</TableCell>
                        <TableCell align="right">
                          <TextField size="small" value={form.denoms[d.id] ?? ''} onChange={(e) => onChange({ denoms: { ...form.denoms, [d.id]: e.target.value.replace(/[^0-9]/g, '') } })}
                            slotProps={{ htmlInput: { inputMode: 'numeric', 'aria-label': `${d.kind} ${d.description} count`, style: { textAlign: 'right' } } }} sx={{ ...box, width: 100 }} />
                        </TableCell>
                        <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{num(form.denoms[d.id] ?? '') ? money(d.amount * num(form.denoms[d.id])) : '—'}</TableCell>
                      </TableRow>
                    ))}
                    <TableRow><TableCell colSpan={2} sx={{ fontWeight: 700 }}>Cash total</TableCell><TableCell align="right" sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>{money(cashTotal)}</TableCell></TableRow>
                  </TableBody>
                </Table>
              )
              : extras.cash.length === 0
                ? <Typography sx={{ p: 2.5, fontSize: 13.5 }} color="text.secondary">No cash denominations were recorded for this transaction.</Typography>
                : (
                  <Table size="small" stickyHeader aria-label="Cash denominations">
                    <TableHead><TableRow><TableCell>Denomination</TableCell><TableCell align="right">Count</TableCell><TableCell align="right">Amount</TableCell></TableRow></TableHead>
                    <TableBody>
                      {extras.cash.map((c) => (
                        <TableRow key={`${c.kind}-${c.denomination}`} hover>
                          <TableCell>{c.kind} {c.description}</TableCell>
                          <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{c.qty}</TableCell>
                          <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{money(c.amount)}</TableCell>
                        </TableRow>
                      ))}
                      <TableRow><TableCell colSpan={2} sx={{ fontWeight: 700 }}>Cash total</TableCell><TableCell align="right" sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>{money(extras.cash.reduce((a, c) => a + c.amount, 0))}</TableCell></TableRow>
                    </TableBody>
                  </Table>
                )
          )}
        </Box>

        <Box sx={{ display: 'flex', gap: 3, px: 2, py: 1, borderTop: 1, borderColor: 'divider', alignItems: 'center', flexWrap: 'wrap' }} role="status">
          {editing && onBalance && (
            <Box sx={{ display: 'flex', gap: 0.75, mr: 'auto' }}>
              <Tooltip title={balanced ? 'Debits already equal credits' : 'Add an entry for the difference (Ctrl+B)'}>
                <span><Button size="small" variant="outlined" disabled={balanced} onClick={() => onBalance(false)}>Balance</Button></span>
              </Tooltip>
              {cashBalance && (
                <Tooltip title={balanced ? 'Debits already equal credits' : 'Add the difference as a cash entry (Ctrl+Shift+B)'}>
                  <span><Button size="small" variant="outlined" disabled={balanced} onClick={() => onBalance(true)}>Balance with cash</Button></span>
                </Tooltip>
              )}
            </Box>
          )}
          <Typography sx={{ fontSize: 13.5, color: balanced ? 'text.primary' : 'error.main' }}>
            Difference: <b style={{ fontVariantNumeric: 'tabular-nums' }}>{money(totals.difference)}</b>{!balanced && ' (debits and credits do not match)'}
          </Typography>
          <Typography sx={{ fontSize: 13.5 }}>Debit: <b style={{ fontVariantNumeric: 'tabular-nums' }}>{money(totals.debit)}</b></Typography>
          <Typography sx={{ fontSize: 13.5 }}>Credit: <b style={{ fontVariantNumeric: 'tabular-nums' }}>{money(totals.credit)}</b></Typography>
        </Box>
      </Box>
    </Box>
  )
}
