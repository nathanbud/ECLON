import { Alert, Box, Button, Checkbox, Chip, Divider, FormControlLabel, IconButton, MenuItem, Stack, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { FiChevronDown, FiChevronUp, FiFilePlus, FiPlus, FiSave, FiSearch, FiTrash2, FiX } from 'react-icons/fi'
import {
  cancelMemo, createJournal, createMemo, getJournal, getMemo, getMemoAccounts, journalStep, listBackOfficeTypes, listJournals, listMemos, memoStep, reverseJournal, reverseMemo,
  searchPostingAccounts, type BackOfficeType, type MemoKind, type MemoLineIn,
} from './api'
import { BackOfficeDisbursement as DisbursementWindow } from './BackOfficeDisbursement'
import { DiscardDialog, EntryList, FindDialog, NotMadeHere, Panel, ReadOnly, ToolButton, Toolbar, Totals, TypePicker, type Entry } from './BackOfficeParts'
import { EntryStepStatus } from './EntryStepStatus'
import { appBackground } from './glass'
import { useLeaveGuard } from './leaveGuard'
import MoneyField from './MoneyField'
import PenaltyWaiversTab from './PenaltyWaiversTab'
import { fmtDate } from './profile/format'
import { useMe } from './session'
import { AccountPicker } from './ui/AccountPicker'
import { BranchSelect } from './ui/BranchSelect'
import { usePageKeys } from './ui/usePageKeys'
import { ConfirmButton } from './ui/dialogs'
import { money } from './ui/format'
import { Gated } from './ui/Gated'
import DocumentsPanel from './DocumentsPanel'

/**
 * Back Office: one entry window for the entries made away from the teller window, after the old system's General Journal Transactions window
 * (pics/backoffice.png): the transaction type on top, the entry's numbers and date beside it, the GL/SL entries with their totals and difference, and the
 * description; Find, New, Save, Cancel and Page Up / Page Down to move between entries. The types are the ones this application can post: the general
 * journal and the debit/credit memo; penalty waivers have their own list. Every entry still goes maker, then reviewer, then poster.
 */

type TypeId = 'GJ' | 'DCM' | 'WAIVER'
/** The Trans. type list is every back-office type of the old system (transtype, module 1), read from the server; a choice is the old type number, or WAIVER. */
const WAIVER = 'WAIVER'
/** The tab name that opens the Disbursement Transactions window (side menu, search). */
const DISBURSEMENTS_TAB = 'Disbursements'
/** What the menu, the search and the inbox name, and the choice it opens: the general journal is old type 1, the memo type 2. */
const BY_TAB: Record<string, string> = { 'General journal': '1', 'Debit/credit memos': '2', 'Penalty waivers': WAIVER }
/** Used until the list comes from the server (or when it cannot): the two this window makes. */
const FALLBACK: BackOfficeType[] = [
  { id: 1, name: 'General Journal', code: 'GJ', entries: 0, lastUsed: null, entry: 'GJ', madeElsewhere: null },
  { id: 2, name: 'Debit/Credit Memo', code: 'DCM', entries: 0, lastUsed: null, entry: 'DCM', madeElsewhere: null },
]
const LABEL: Record<TypeId, string> = { GJ: 'general journal', DCM: 'debit/credit memo', WAIVER: 'penalty waiver' }
const KINDS: [MemoKind, string][] = [['LEDGER', 'Ledger account'], ['SAVINGS', 'Member savings'], ['SHARE', 'Member share capital']]
const today = () => new Date().toLocaleDateString('en-CA')
const dateText = (d: string) => fmtDate(d) ?? d

interface ShownLine { account: string; title: string | null; clientId: number | null; clientName: string | null; debit: number; credit: number }

const listOf = async (type: TypeId, branchCode: number): Promise<Entry[]> => {
  if (type === 'GJ') {
    return (await listJournals(branchCode)).map((j) => ({ id: j.id, reference: j.reference, date: j.journalDate, description: j.description ?? '', status: j.status, createdBy: j.createdBy,
      approvedBy: j.approvedBy, postedBy: null, total: j.total, reversal: false, documentNo: null }))
  }
  return (await listMemos(branchCode)).map((m) => ({ id: m.id, reference: m.reference, date: m.memoDate, description: m.description, status: m.status, createdBy: m.createdBy,
    approvedBy: m.approvedBy, postedBy: m.postedBy, total: m.total, reversal: !!m.reverses, documentNo: m.documentNo }))
}

const detailOf = async (type: TypeId, id: number): Promise<{ lines: ShownLine[]; cancelReason: string | null }> => {
  if (type === 'GJ') {
    const d = await getJournal(id)
    return { lines: d.lines.map((l) => ({ account: l.accountCode, title: null, clientId: null, clientName: null, debit: l.debit, credit: l.credit })), cancelReason: null }
  }
  const d = await getMemo(id)
  return {
    lines: d.lines.map((l) => ({
      account: l.kind === 'LEDGER' ? l.accountCode ?? '' : `${l.type ?? (l.kind === 'SHARE' ? 'Share capital' : 'Savings')} ${l.refText ?? l.refNo ?? ''}`,
      title: l.kind === 'LEDGER' ? l.accountTitle : null, clientId: l.clientId, clientName: l.clientName, debit: l.debit, credit: l.credit,
    })),
    cancelReason: d.cancelReason,
  }
}

/** The Back Office tile: the old system's two windows side by side as tabs. A tab name from the menu, the search or the inbox picks the window and its type. */
export default function BackOfficePage({ initialTab }: { initialTab?: string }) {
  const me = useMe()
  const [window_, setWindow] = useState<'journal' | 'disbursement'>(() => (initialTab === DISBURSEMENTS_TAB ? 'disbursement' : 'journal'))
  const [branchCode, setBranchCode] = useState(me.branchCode ?? 1)
  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1180, mx: 'auto' }}>
        <Stack direction="row" spacing={2} sx={{ mb: 0.5, alignItems: 'center' }}>
          <Typography component="h1" sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em', flex: 1 }}>Back Office</Typography>
          {window_ === 'journal' && <BranchSelect value={branchCode} onChange={setBranchCode} />}
        </Stack>
        <Tabs value={window_} onChange={(_, v) => setWindow(v)} sx={{ mb: 1.5 }} aria-label="Back Office windows">
          <Tab value="journal" label="General Journal Transactions" />
          <Tab value="disbursement" label="Disbursement Transactions" />
        </Tabs>
        {window_ === 'journal'
          ? <JournalWindow key={branchCode} initialTab={initialTab} branchCode={branchCode} />
          : <DisbursementWindow />}
      </Box>
    </Box>
  )
}

/** The General Journal Transactions window (pics/backoffice.png). */
function JournalWindow({ initialTab, branchCode }: { initialTab?: string; branchCode: number }) {
  const me = useMe()
  const [choice, setChoice] = useState<string>(() => BY_TAB[initialTab ?? ''] ?? '1')
  const typesQ = useQuery({ queryKey: ['backoffice-types', 1], queryFn: () => listBackOfficeTypes(1), staleTime: 10 * 60_000, retry: false })
  const types = typesQ.data && typesQ.data.length > 0 ? typesQ.data : FALLBACK
  const old = types.find((t) => String(t.id) === choice) ?? null
  // what the window does with the choice: make journals or memos, show the waivers, or (null) say the type is not made here
  const type: TypeId | null = choice === WAIVER ? 'WAIVER' : old?.entry === 'GJ' || old?.entry === 'DCM' ? old.entry : null
  const [openId, setOpenId] = useState<number | null>(null)
  const [making, setMaking] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [finding, setFinding] = useState(false)
  const entry = type === 'GJ' || type === 'DCM'
  const list = useQuery({ queryKey: ['backoffice', type, branchCode], queryFn: () => listOf(type as TypeId, branchCode), enabled: entry && branchCode > 0 })
  const entries = list.data ?? []
  const at = openId === null ? -1 : entries.findIndex((e) => e.id === openId)
  const shown = at >= 0 ? entries[at] : null

  useLeaveGuard(making && dirty ? 'The new entry has not been saved.' : null)
  // a move away from an entry being made asks first while it holds something typed
  const [discarding, setDiscarding] = useState<(() => void) | null>(null)
  const leaveNew = (move: () => void) => { if (making && dirty) setDiscarding(() => move); else move() }
  const choose = (c: string) => { if (c !== choice) leaveNew(() => { setChoice(c); setOpenId(null); setMaking(false); setDirty(false) }) }
  const open = (id: number) => leaveNew(() => { setMaking(false); setDirty(false); setOpenId(id) })
  const startNew = () => leaveNew(() => { setOpenId(null); setDirty(false); setMaking(true) })
  // Page Up / Page Down: the entry above or below in the list (newest first), as in the old window
  const step = (d: -1 | 1) => {
    if (entries.length === 0) return
    const next = at < 0 ? (d === 1 ? 0 : entries.length - 1) : at + d
    if (next >= 0 && next < entries.length) open(entries[next].id)
  }
  usePageKeys(entry, step)

  return (
    <>
      <Box>

        {entry && (
          <Toolbar>
            <ToolButton icon={<FiSearch />} label="Find" onClick={() => setFinding(true)} />
            <ToolButton icon={<FiFilePlus />} label="New" disabled={making} onClick={startNew} />
            <ToolButton icon={<FiSave />} label="Save" form="backoffice-entry" type="submit" disabled={!making} />
            <ToolButton icon={<FiX />} label="Cancel" disabled={!making} onClick={() => leaveNew(() => { setMaking(false); setDirty(false) })} />
            <Divider orientation="vertical" flexItem sx={{ mx: 0.5 }} />
            <ToolButton icon={<FiChevronUp />} label="Previous" hint="Page Up" disabled={making || entries.length === 0 || at === 0} onClick={() => step(-1)} />
            <ToolButton icon={<FiChevronDown />} label="Next" hint="Page Down" disabled={making || entries.length === 0 || at === entries.length - 1} onClick={() => step(1)} />
            {shown && !making && <Typography sx={{ fontSize: 12, ml: 1 }} color="text.secondary">{at + 1} of {entries.length}</Typography>}
          </Toolbar>
        )}

        <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} sx={{ mb: 1.5 }}>
          <Panel sx={{ flex: 3 }}>
            <Stack direction="row" spacing={2} sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 1.5 }}>
              <TypePicker types={types} value={choice} onChange={choose} extra={{ value: WAIVER, label: 'Penalty Waiver', note: 'Waiver journals for loan payments charged less penalty' }} />
              {entry && <ReadOnly label="Control No." value={making ? 'Assigned when saved' : shown?.reference ?? '—'} />}
              {entry && shown && !making && <EntryStepStatus status={shown.status} />}
              {shown?.reversal && !making && <Chip size="small" variant="outlined" label="Reversal" />}
            </Stack>
          </Panel>
          {entry && !making && shown && (
            <Panel sx={{ flex: 2 }}>
              <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
                <ReadOnly label="Trans. date" value={dateText(shown.date)} />
                <ReadOnly label="Made by" value={shown.createdBy} />
                {shown.approvedBy && <ReadOnly label="Approved by" value={shown.approvedBy} />}
                {shown.postedBy && <ReadOnly label="Posted by" value={shown.postedBy} />}
                {shown.documentNo && <ReadOnly label="Document" value={shown.documentNo} />}
              </Stack>
            </Panel>
          )}
        </Stack>

        {type === 'WAIVER' && <PenaltyWaiversTab />}
        {type === null && old && <NotMadeHere type={old} otherwise="Use a General Journal or a Debit/Credit Memo where one fits." />}
        {entry && making && (
          <NewEntry key={type + branchCode} type={type as TypeId} branchCode={branchCode} onDirty={setDirty}
            onSaved={(id) => { setMaking(false); setDirty(false); void list.refetch().then(() => setOpenId(id)) }} />
        )}
        {entry && !making && shown && <EntryView type={type as TypeId} entry={shown} user={me.username} admin={me.admin} onChanged={() => void list.refetch()} onOpen={(id) => { void list.refetch().then(() => setOpenId(id)) }} />}
        {entry && !making && !shown && (
          <Panel>
            <Typography sx={{ fontSize: 13, mb: 1.5 }} color="text.secondary">Press New to make a {LABEL[type as TypeId]}, or open one below (Find searches them all).</Typography>
            <EntryList entries={entries.slice(0, 12)} loading={list.isLoading} error={list.error as Error | null} onOpen={open} />
          </Panel>
        )}
      </Box>
      {finding && <FindDialog entries={entries} loading={list.isLoading} error={list.error as Error | null} typeLabel={LABEL[type as TypeId] ?? ''}
        onClose={() => setFinding(false)} onOpen={(id) => { setFinding(false); open(id) }} />}
      {discarding && <DiscardDialog onKeep={() => setDiscarding(null)} onDiscard={() => { const move = discarding; setDiscarding(null); move() }} />}
    </>
  )
}

// ---- pieces of the window -----------------------------------------------------------------------------------------

// ---- making an entry ----------------------------------------------------------------------------------------------

interface DraftLine { kind: MemoKind; account: string; accountName: string; client: string; memberAccount: string; debit: string; credit: string }
const blankLine = (): DraftLine => ({ kind: 'LEDGER', account: '', accountName: '', client: '', memberAccount: '', debit: '', credit: '' })

/** A member line of a memo: the member's number, then a choice among that member's accounts of the line's kind. */
function MemberAccount({ branchCode, line, onChange }: { branchCode: number; line: DraftLine; onChange: (patch: Partial<DraftLine>) => void }) {
  const [looked, setLooked] = useState('')
  const id = Number(looked)
  const found = useQuery({ queryKey: ['memo-accounts', branchCode, id], queryFn: () => getMemoAccounts(branchCode, id), enabled: Number.isInteger(id) && id > 0, retry: false })
  const mine = (found.data?.accounts ?? []).filter((a) => a.kind === line.kind && a.status !== 17)
  const look = () => { if (line.client.trim() !== looked) { setLooked(line.client.trim()); onChange({ memberAccount: '' }) } }
  return (
    <Stack direction="row" spacing={1} sx={{ minWidth: 0 }}>
      <TextField size="small" label="Member ID" value={line.client} onChange={(e) => onChange({ client: e.target.value.replace(/\D/g, '') })} onBlur={look} onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); look() } }} sx={{ width: 110 }} />
      <TextField
        select size="small" label={found.data ? found.data.clientName : 'Account'} value={line.memberAccount} onChange={(e) => onChange({ memberAccount: e.target.value })} sx={{ flex: 1, minWidth: 180 }}
        error={found.isError} helperText={found.isError ? 'No such member' : found.data && mine.length === 0 ? 'No account of this kind' : undefined} disabled={!found.data || mine.length === 0}
      >
        {mine.map((a) => <MenuItem key={`${a.typeCode}/${a.refNo}`} value={`${a.typeCode}/${a.refNo}`}>{a.type ?? 'Account'} {a.refText ?? a.refNo} · {money(a.balance)}</MenuItem>)}
      </TextField>
    </Stack>
  )
}

function NewEntry({ type, branchCode, onDirty, onSaved }: { type: TypeId; branchCode: number; onDirty: (d: boolean) => void; onSaved: (id: number) => void }) {
  const memo = type === 'DCM'
  const me = useMe()
  const [reference, setReference] = useState('')
  const [date, setDate] = useState(today())
  const [description, setDescription] = useState('')
  const [lines, setLines] = useState<DraftLine[]>([blankLine(), blankLine()])
  // A journal may not change a control account's balance (members' accounts would no longer add up to it); an administrator can, with a written reason.
  const [direct, setDirect] = useState(false)
  const [directReason, setDirectReason] = useState('')
  const touch = () => onDirty(true)
  const set = (i: number, patch: Partial<DraftLine>) => { touch(); setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...patch } : l))) }
  const dr = lines.reduce((s, l) => s + (Number(l.debit) || 0), 0)
  const cr = lines.reduce((s, l) => s + (Number(l.credit) || 0), 0)
  const difference = Math.round((dr - cr) * 100) / 100
  const balanced = difference === 0 && dr > 0
  const lineDone = (l: DraftLine) => (l.kind === 'LEDGER' ? !!l.account.trim() : !!l.memberAccount) && (Number(l.debit) > 0) !== (Number(l.credit) > 0)
  const missing = [
    !memo && !reference.trim() ? 'the Ref. Number' : null,
    memo && description.trim().length < 5 ? 'the description (the reason and who authorised it)' : null,
    !lines.every(lineDone) ? 'every entry (an account and either a debit or a credit)' : null,
    !balanced ? 'equal debits and credits' : null,
    !memo && direct && directReason.trim().length < 10 ? 'why this journal changes a control account (at least 10 characters)' : null,
  ].filter(Boolean) as string[]
  const save = useMutation({
    meta: { done: memo ? 'Memo saved as a draft.' : 'Journal saved as a draft.' },
    mutationFn: async () => {
      if (memo) {
        const out = lines.map<MemoLineIn>((l) => {
          const [t, ref] = l.memberAccount.split('/')
          return l.kind === 'LEDGER'
            ? { kind: 'LEDGER', clientId: null, typeCode: null, refNo: null, accountCode: l.account.trim(), debit: Number(l.debit) || 0, credit: Number(l.credit) || 0, note: null }
            : { kind: l.kind, clientId: Number(l.client) || null, typeCode: t ? Number(t) : null, refNo: ref ? Number(ref) : null, accountCode: null, debit: Number(l.debit) || 0, credit: Number(l.credit) || 0, note: null }
        })
        return (await createMemo({ branchCode, memoDate: date, description: description.trim(), lines: out })).id
      }
      return (await createJournal({ branchCode, reference: reference.trim(), journalDate: date, description: description.trim(),
        lines: lines.map((l) => ({ accountCode: l.account.trim(), debit: Number(l.debit) || 0, credit: Number(l.credit) || 0 })),
        controlAccountReason: direct ? directReason.trim() : null })).id
    },
    onSuccess: onSaved,
  })
  return (
    <Box component="form" id="backoffice-entry" onSubmit={(e) => { e.preventDefault(); if (missing.length === 0 && !save.isPending) save.mutate() }}>
      <Panel sx={{ mb: 1.5 }}>
        <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap', rowGap: 1.5 }}>
          {memo
            ? <ReadOnly label="Ref. Number" value="Assigned when saved" />
            : <TextField size="small" label="Ref. Number" required value={reference} onChange={(e) => { touch(); setReference(e.target.value) }} slotProps={{ htmlInput: { maxLength: 40 } }} />}
          <TextField size="small" type="date" label="Trans. date" value={date} onChange={(e) => { touch(); setDate(e.target.value) }} slotProps={{ inputLabel: { shrink: true }, htmlInput: memo ? { max: today() } : undefined }} />
        </Stack>
      </Panel>

      <Panel sx={{ mb: 1.5 }}>
        <Typography component="h2" sx={{ fontSize: 13, fontWeight: 700, mb: 1 }}>GL/SL Entries</Typography>
        {memo && <Typography sx={{ fontSize: 12, mb: 1 }} color="text.secondary">On a member&rsquo;s account a debit takes money out and a credit puts it in.</Typography>}
        <Table size="small" aria-label="GL/SL entries">
          <TableHead>
            <TableRow>
              <TableCell sx={{ width: 32 }}>#</TableCell>
              {memo && <TableCell sx={{ width: 190 }}>Applies to</TableCell>}
              <TableCell>{memo ? 'Account / member' : 'Account'}</TableCell>
              <TableCell align="right" sx={{ width: 150 }}>Debit</TableCell>
              <TableCell align="right" sx={{ width: 150 }}>Credit</TableCell>
              <TableCell sx={{ width: 44 }} />
            </TableRow>
          </TableHead>
          <TableBody>
            {lines.map((l, i) => (
              <TableRow key={i}>
                <TableCell>{i + 1}</TableCell>
                {memo && (
                  <TableCell>
                    <TextField select size="small" fullWidth label="Applies to" value={l.kind} onChange={(e) => set(i, { kind: e.target.value as MemoKind, account: '', accountName: '', memberAccount: '' })}>
                      {KINDS.map(([k, label]) => <MenuItem key={k} value={k}>{label}</MenuItem>)}
                    </TextField>
                  </TableCell>
                )}
                <TableCell>
                  {l.kind === 'LEDGER'
                    ? <AccountPicker code={l.account} desc={l.accountName} label={`Entry ${i + 1} account`} width={320} placeholder="Account number or name"
                        search={(q) => searchPostingAccounts(branchCode, q)} recentKey="coop.journalRecentAccounts"
                        onPick={(a) => set(i, { account: a?.code ?? '', accountName: a?.description ?? '' })} />
                    : <MemberAccount key={l.kind} branchCode={branchCode} line={l} onChange={(patch) => set(i, patch)} />}
                </TableCell>
                <TableCell><MoneyField size="small" label="Debit" value={l.debit} onValueChange={(v) => set(i, { debit: v })} fullWidth /></TableCell>
                <TableCell><MoneyField size="small" label="Credit" value={l.credit} onValueChange={(v) => set(i, { credit: v })} fullWidth /></TableCell>
                <TableCell><IconButton aria-label={`Remove entry ${i + 1}`} disabled={lines.length <= 2} onClick={() => { touch(); setLines((ls) => ls.filter((_, j) => j !== i)) }}><FiTrash2 /></IconButton></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        <Button size="small" startIcon={<FiPlus />} sx={{ mt: 1 }} onClick={() => { touch(); setLines((ls) => [...ls, blankLine()]) }}>Add entry</Button>
        <Totals debit={dr} credit={cr} />
      </Panel>

      <Panel>
        <TextField fullWidth multiline minRows={3} size="small" label={memo ? 'Description: the reason and who authorised it' : 'Description'} value={description}
          onChange={(e) => { touch(); setDescription(e.target.value) }} slotProps={{ htmlInput: { maxLength: memo ? 200 : 255 } }} />
        {!memo && me.admin && (
          <Box sx={{ mt: 1 }}>
            <FormControlLabel
              control={<Checkbox size="small" checked={direct} onChange={(e) => { touch(); setDirect(e.target.checked) }} />}
              label={<Typography sx={{ fontSize: 13 }}>This journal changes a control account directly (administrators only)</Typography>}
            />
            {direct && (
              <>
                <Typography sx={{ fontSize: 12, mb: 1 }} color="text.secondary">
                  A journal changes the general ledger only, so a control account such as Savings Deposit would no longer equal the members&rsquo; accounts.
                  Use a debit/credit memo to change members&rsquo; balances. Use this only for an opening balance or an approved correction; the reason is kept in the audit log.
                </Typography>
                <TextField fullWidth size="small" label="Why it changes a control account" value={directReason} required
                  onChange={(e) => { touch(); setDirectReason(e.target.value) }} slotProps={{ htmlInput: { maxLength: 250 } }} />
              </>
            )}
          </Box>
        )}
        {missing.length > 0 && <Typography sx={{ fontSize: 12, mt: 1 }} color="text.secondary">To save, fill in {missing.join(', ')}.</Typography>}
        {save.error && <Alert severity="error" sx={{ mt: 1 }}>{(save.error as Error).message}</Alert>}
        <Stack direction="row" sx={{ mt: 1.5, justifyContent: 'flex-end' }}>
          <Button type="submit" variant="contained" startIcon={<FiSave />} disabled={missing.length > 0 || save.isPending}>Save draft</Button>
        </Stack>
      </Panel>
    </Box>
  )
}

// ---- one saved entry ----------------------------------------------------------------------------------------------

function EntryView({ type, entry, user, admin, onChanged, onOpen }: { type: TypeId; entry: Entry; user: string; admin: boolean; onChanged: () => void; onOpen: (id: number) => void }) {
  const qc = useQueryClient()
  const memo = type === 'DCM'
  const noun = memo ? 'memo' : 'journal'
  const detail = useQuery({ queryKey: ['backoffice-entry', type, entry.id], queryFn: () => detailOf(type, entry.id) })
  const [asking, setAsking] = useState<'cancel' | 'reverse' | null>(null)
  const [reason, setReason] = useState('')
  const done = () => { void qc.invalidateQueries({ queryKey: ['backoffice-entry', type, entry.id] }); onChanged() }
  const step = useMutation({
    meta: { done: (_, s) => ({ submit: `The ${noun} was sent for review.`, approve: 'Approved. A third person can now post it.', post: `The ${noun} was posted.` } as Record<string, string>)[s as string] ?? null },
    mutationFn: async (s: 'submit' | 'approve' | 'post'): Promise<unknown> => (memo ? memoStep(entry.id, s) : journalStep(entry.id, s)),
    onSuccess: done,
  })
  const ask = useMutation({
    meta: { done: (r) => (asking === 'cancel' ? `The ${noun} was cancelled.` : `Reversal draft ${(r as { reference: string }).reference} made. It goes through review and approval like any ${noun}.`) },
    mutationFn: async (): Promise<{ id: number; reference: string }> => (asking === 'cancel' ? cancelMemo(entry.id, reason.trim()) : memo ? reverseMemo(entry.id, reason.trim()) : reverseJournal(entry.id, reason.trim())),
    onSuccess: (r) => { const wasReverse = asking === 'reverse'; setAsking(null); setReason(''); done(); if (wasReverse && r.id !== entry.id) onOpen(r.id) },
  })
  const me = user.toLowerCase()
  const maker = entry.createdBy.toLowerCase() === me
  const approver = entry.approvedBy?.toLowerCase() === me
  const lines = detail.data?.lines ?? []
  const dr = lines.reduce((t, l) => t + l.debit, 0)
  const cr = lines.reduce((t, l) => t + l.credit, 0)
  const error = (step.error ?? ask.error) as Error | null
  const s = entry.status

  return (
    <>
      <Panel sx={{ mb: 1.5 }}>
        <Typography component="h2" sx={{ fontSize: 13, fontWeight: 700, mb: 1 }}>GL/SL Entries</Typography>
        <Table size="small" aria-label="GL/SL entries">
          <TableHead>
            <TableRow>
              <TableCell sx={{ width: 32 }}>#</TableCell><TableCell>Account</TableCell>
              {memo && <><TableCell>Member ID</TableCell><TableCell>Name of member</TableCell></>}
              <TableCell align="right">Debit</TableCell><TableCell align="right">Credit</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {detail.isLoading && <TableRow><TableCell colSpan={memo ? 6 : 4}>Loading…</TableCell></TableRow>}
            {detail.error && <TableRow><TableCell colSpan={memo ? 6 : 4}><Alert severity="error">{(detail.error as Error).message}</Alert></TableCell></TableRow>}
            {lines.map((l, i) => (
              <TableRow key={i}>
                <TableCell>{i + 1}</TableCell>
                <TableCell>{l.account}{l.title ? <Typography component="span" sx={{ fontSize: 12, ml: 1 }} color="text.secondary">{l.title}</Typography> : null}</TableCell>
                {memo && <><TableCell>{l.clientId ?? ''}</TableCell><TableCell>{l.clientName ?? ''}</TableCell></>}
                <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{l.debit ? money(l.debit) : ''}</TableCell>
                <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{l.credit ? money(l.credit) : ''}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {detail.data && <Totals debit={dr} credit={cr} />}
      </Panel>

      <Panel>
        <Typography sx={{ fontSize: 11 }} color="text.secondary">Description</Typography>
        <Typography sx={{ fontSize: 13, whiteSpace: 'pre-wrap', mb: 1.5 }}>{entry.description || '—'}</Typography>
        {detail.data?.cancelReason && <Alert severity="info" sx={{ mb: 1.5 }}>Cancelled: {detail.data.cancelReason}</Alert>}
        {s === 'FOR_REVIEW' && maker && <Alert severity="info" sx={{ mb: 1.5 }}>Someone other than the person who made this {noun} has to approve it.</Alert>}
        {memo && s === 'APPROVED' && (maker || approver) && <Alert severity="info" sx={{ mb: 1.5 }}>A third person, who neither made nor approved it, has to post it.</Alert>}
        {asking && <TextField fullWidth autoFocus size="small" label={asking === 'cancel' ? 'Reason for cancelling' : 'Reason for the reversal'} value={reason} onChange={(e) => setReason(e.target.value)} sx={{ mb: 1.5 }} />}
        {error && <Alert severity="error" sx={{ mb: 1.5 }}>{error.message}</Alert>}
        <Stack direction="row" spacing={1} sx={{ justifyContent: 'flex-end', flexWrap: 'wrap', rowGap: 1 }}>
          {asking && <Button onClick={() => { setAsking(null); setReason('') }}>Back</Button>}
          {memo && ['DRAFT', 'FOR_REVIEW', 'APPROVED'].includes(s) && !asking && (maker || admin) && <Button color="warning" onClick={() => setAsking('cancel')}>Cancel memo</Button>}
          {s === 'DRAFT' && !asking && <Button variant="contained" disabled={step.isPending} onClick={() => step.mutate('submit')}>Send for review</Button>}
          {s === 'FOR_REVIEW' && !asking && (
            <Gated allowed={!maker} reason={`You made this ${noun}. Someone else has to approve it.`}>
              <Button variant="contained" disabled={step.isPending} onClick={() => step.mutate('approve')}>Approve</Button>
            </Gated>
          )}
          {s === 'APPROVED' && !asking && (
            <Gated allowed={!memo || (!maker && !approver)} reason="You made or approved this memo. A third person has to post it.">
              <ConfirmButton variant="contained" disabled={step.isPending} onConfirm={() => step.mutate('post')} title={`Post ${noun} ${entry.reference}?`}
                message={<>It writes <b>{money(entry.total)}</b> {memo ? 'to members’ accounts and the ledger' : 'to the general ledger'}, dated {fmtDate(entry.date)}. A posted {noun} cannot be edited; a mistake is corrected with a reversal.</>}>Post</ConfirmButton>
            </Gated>
          )}
          {s === 'POSTED' && !asking && <Button color="warning" onClick={() => setAsking('reverse')}>Reverse…</Button>}
          {asking && <Button variant="contained" color="warning" disabled={ask.isPending || reason.trim().length < 3} onClick={() => ask.mutate()}>{asking === 'cancel' ? 'Cancel the memo' : 'Make the reversal draft'}</Button>}
        </Stack>
      </Panel>

      <Panel>
        <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 1 }}>Supporting documents</Typography>
        <DocumentsPanel entityType="JOURNAL" entityId={entry.reference} />
      </Panel>
    </>
  )
}
