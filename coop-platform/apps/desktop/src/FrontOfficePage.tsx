import {
  Alert, Badge, Box, Button, Chip, CircularProgress, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, Skeleton, Snackbar,
  Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, TextField, Typography,
} from '@mui/material'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { FiChevronDown, FiChevronUp, FiCopy, FiEdit2, FiPlus, FiPrinter, FiSave, FiSearch, FiTrash2, FiX } from 'react-icons/fi'
import {
  createFoDraft, deleteFoDraft, getFoDraft, getFoDuplicates, getFoSetup, getFoTellerTotals, getFoTransaction, getLookups, listFoTransactions, updateFoDraft,
  type FoClientAccount, type FoColumn, type FoDraftPayload, type FoDraftRow, type FoSetup, type FoSummary,
} from './api'
import CashHandoverPanel from './CashHandoverPanel'
import DrawerClose from './DrawerClose'
import FoFindDialog from './FoFindDialog'
import { LoanApplicationsPanel } from './LoanApplicationsPanel'
import { LoanReleasePanel } from './LoanReleasePanel'
import { OverrideAsk, OverridesCard } from './Overrides'
import { usePulse } from './pulse'
import ReceiptDialog from './Receipt'
import TemplatesMenu, { useTemplates, type FoTemplate } from './Templates'
import FoTransactionForm, { emptyForm, formTotals, money, newLine, type FoExtras, type FoForm } from './FoTransactionForm'
import { appBackground, glass } from './glass'
import { useMe } from './session'
import ToolbarMenu, { type MenuEntry } from './ToolbarMenu'
import { ToolbarPortal } from './toolbarSlot'

const isMac = /Mac|iPhone|iPad/.test(navigator.platform)
const MOD = isMac ? '⌘' : 'Ctrl+'
const mod = (e: KeyboardEvent) => (isMac ? e.metaKey : e.ctrlKey)

type Shown =
  | { kind: 'none' }
  | { kind: 'txn'; year: number; module: number; code: number; ctlNo: number }
  | { kind: 'draft'; id: number | null }

const NO_EXTRAS: FoExtras = { ctl: '', time: '', cheques: [], allocations: [], cash: [] }
const num = (s: string) => {
  const n = Number(s.replace(/,/g, ''))
  return Number.isFinite(n) ? n : 0
}

const toPayload = (f: FoForm): FoDraftPayload => ({
  trCode: f.trCode ?? 0, date: f.date, batchNo: f.batchNo, dept: f.dept, clientId: f.clientId, clientName: f.clientName, refNo: f.refNo, explanation: f.explanation,
  lines: f.lines.filter((l) => l.accountCode || num(l.debit) || num(l.credit))
    .map((l) => ({ accountCode: l.accountCode, accountDesc: l.accountDesc, acctNum: l.acctNum, debit: l.debit || '0', credit: l.credit || '0', dept: l.dept ?? f.dept, slc: l.slc ?? null, slt: l.slt ?? null })),
  denominations: Object.entries(f.denoms).filter(([, q]) => q).map(([id, qty]) => ({ typeId: Number(id), qty })),
})

const fromPayload = (p: FoDraftPayload): FoForm => ({
  trCode: p.trCode, date: p.date, batchNo: p.batchNo, dept: p.dept, clientId: p.clientId, clientName: p.clientName ?? '', refNo: p.refNo, explanation: p.explanation,
  lines: p.lines.length
    ? p.lines.map((l) => ({ ...newLine(), accountCode: l.accountCode, accountDesc: l.accountDesc, acctNum: l.acctNum, debit: num(l.debit) ? l.debit : '', credit: num(l.credit) ? l.credit : '', dept: l.dept, slc: l.slc ?? null, slt: l.slt ?? null }))
    : [newLine(), newLine()],
  denoms: Object.fromEntries(p.denominations.map((d) => [d.typeId, d.qty])),
})

/** The current time, ticking every second. */
function useNow() {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 1000)
    return () => clearInterval(t)
  }, [])
  return now
}

const longDate = (iso: string) => new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, { weekday: 'short', year: 'numeric', month: 'long', day: 'numeric' })

/**
 * "Teller's / Cashier's totals", as in the old summary tab: what the teller started with, took in and paid out, what is
 * pending, and what should be in the drawer, for cash and for cheques not yet banked (COCI).
 */
function TellerTotalsCard({ date, onDate, totals }: { date: string; onDate: (d: string) => void; totals: ReturnType<typeof getFoTellerTotals> extends Promise<infer T> ? T : never }) {
  const now = useNow()
  const rows: { label: string; pick: (c: FoColumn) => number; bold?: boolean; indent?: boolean; heading?: boolean }[] = [
    { label: 'Beginning Balance', pick: (c) => c.beginning },
    { label: 'IN', pick: (c) => c.in },
    { label: 'OUT', pick: (c) => c.out },
    { label: 'Pending', pick: () => 0, heading: true },
    { label: 'IN', pick: (c) => c.pendingIn, indent: true },
    { label: 'OUT', pick: (c) => c.pendingOut, indent: true },
    { label: 'Ending Balance', pick: (c) => c.ending, bold: true },
  ]
  return (
    <Box sx={(t) => ({ p: 2, flexShrink: 0, ...glass(t) })}>
      <Box sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 2, flexWrap: 'wrap', mb: 1.5 }}>
        <Typography sx={{ fontSize: 14, fontWeight: 600, maxWidth: 240 }}>{totals.person ?? ''}</Typography>
        <Box sx={{ textAlign: 'right' }}>
          <TextField label="Date" type="date" size="small" value={date} onChange={(e) => onDate(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} sx={{ width: 170 }} />
          <Typography sx={{ fontSize: 12.5, mt: 0.5, fontVariantNumeric: 'tabular-nums' }} color="text.secondary">
            {longDate(date)} · {now.toLocaleTimeString()}
          </Typography>
        </Box>
      </Box>
      <Typography component="h2" sx={{ textAlign: 'center', fontSize: 17, fontWeight: 700, letterSpacing: '0.04em', mb: 1.5 }}>TELLER’S / CASHIER’S TOTALS</Typography>
      <Table size="small" aria-label="Teller totals" sx={{ '& td, & th': { borderBottom: 'none', py: 0.6 } }}>
        <TableHead>
          <TableRow>
            <TableCell />
            {['CASH', 'COCI', 'TOTAL'].map((h) => <TableCell key={h} align="right" sx={{ fontSize: 14, fontWeight: 700, letterSpacing: '0.06em' }}>{h}</TableCell>)}
          </TableRow>
        </TableHead>
        <TableBody>
          {rows.map((r, i) => (
            <TableRow key={`${r.label}-${i}`} sx={r.bold ? { '& td': { borderTop: '1px solid', borderColor: 'divider', fontWeight: 700 } } : undefined}>
              <TableCell sx={{ pl: r.indent ? 4 : 2, fontSize: 14, fontWeight: r.bold || r.heading ? 700 : 400 }}>{r.label}{r.heading ? ':' : ''}</TableCell>
              {[totals.cash, totals.coci, totals.total].map((c, k) => (
                <TableCell key={k} align="right" sx={{ fontSize: 14, fontVariantNumeric: 'tabular-nums' }}>{r.heading ? '' : money(r.pick(c))}</TableCell>
              ))}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Box>
  )
}

/** The summary tab: the teller's totals, a count of the drawer, and the day's transactions. Choosing one opens it. */
function SummaryPanel({ setup, onOpen }: { setup: FoSetup; onOpen: (t: FoSummary, all: FoSummary[]) => void }) {
  const [date, setDate] = useState(setup.systemDate)
  const ids = useMemo(() => setup.tabs.flatMap((t) => t.types).filter((t) => t.canFind).map((t) => t.id), [setup])
  const totals = useQuery({ queryKey: ['fo-totals', date], queryFn: () => getFoTellerTotals(date), staleTime: 30_000 })
  const { data, error, isFetching } = useQuery({ queryKey: ['fo-summary', date, ids], queryFn: () => listFoTransactions(date, ids, ''), enabled: ids.length > 0 })
  const rows = useMemo(() => data ?? [], [data])

  return (
    <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: 'minmax(380px, 560px) minmax(0, 1fr)', flex: 1, minHeight: 0 }}>
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5, minHeight: 0, overflow: 'auto', pr: 0.25 }}>
        <OverridesCard />
        {totals.error && <Alert severity="error">{(totals.error as Error).message}</Alert>}
        {totals.data && totals.data.known && (
          <>
            <TellerTotalsCard date={date} onDate={setDate} totals={totals.data} />
            <DrawerClose setup={setup} date={date} />
          </>
        )}
        {totals.data && !totals.data.known && (
          <Box sx={(t) => ({ p: 2, ...glass(t) })}>
            <TextField label="Date" type="date" size="small" value={date} onChange={(e) => setDate(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} sx={{ mb: 1.5 }} />
            <Alert severity="info">{totals.data.reason}</Alert>
          </Box>
        )}
        {totals.isLoading && <Skeleton variant="rounded" height={300} />}
      </Box>

      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1, minHeight: 0, minWidth: 0 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase' }} color="text.secondary">Transactions of the day</Typography>
          {isFetching && <CircularProgress size={16} aria-label="Loading" />}
        </Box>
        {error && <Alert severity="error">{(error as Error).message}</Alert>}
        <Box sx={(t) => ({ flex: 1, minHeight: 0, overflow: 'auto', ...glass(t) })}>
          <Table size="small" stickyHeader aria-label="Transactions of the day">
            <TableHead>
              <TableRow><TableCell>Time</TableCell><TableCell>Type</TableCell><TableCell>Control no.</TableCell><TableCell>Client</TableCell><TableCell align="right">Amount</TableCell></TableRow>
            </TableHead>
            <TableBody>
              {rows.map((r) => (
                <TableRow
                  key={`${r.year}-${r.module}-${r.code}-${r.ctlNo}`} hover tabIndex={0} sx={{ cursor: 'pointer' }}
                  onClick={() => onOpen(r, rows)} onKeyDown={(e) => { if (e.key === 'Enter') onOpen(r, rows) }}
                >
                  <TableCell sx={{ fontVariantNumeric: 'tabular-nums' }}>{r.time?.slice(0, 5)}</TableCell><TableCell>{r.type}</TableCell><TableCell>{r.ctlNo}</TableCell>
                  <TableCell sx={{ maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.clientName ?? '—'}</TableCell>
                  <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{money(r.total)}</TableCell>
                </TableRow>
              ))}
              {!isFetching && rows.length === 0 && <TableRow><TableCell colSpan={5}><Typography sx={{ p: 2, textAlign: 'center' }} color="text.secondary">No transactions on this date.</Typography></TableCell></TableRow>}
            </TableBody>
          </Table>
          {rows.length >= 200 && <Typography sx={{ p: 1.5, fontSize: 12.5 }} color="text.secondary">Showing the latest 200 of the day.</Typography>}
        </Box>
      </Box>
    </Box>
  )
}

/**
 * The teller / front office window, after the old "Front Office Transaction Processing" screen. Posted transactions are
 * shown read-only. New transactions are drafts: checked (accounts exist, debits and credits are counted) and kept for
 * later, but never posted. Nothing here writes to the ledger.
 */
export default function FrontOfficePage() {
  const qc = useQueryClient()
  const me = useMe()
  const { data: setup, error, isLoading } = useQuery({ queryKey: ['fo-setup'], queryFn: getFoSetup, staleTime: 5 * 60_000 })
  const { data: lk } = useQuery({ queryKey: ['lookups'], queryFn: getLookups, staleTime: Infinity })

  const [tab, setTab] = useState('receipt')
  const [shown, setShown] = useState<Shown>({ kind: 'none' })
  const [form, setForm] = useState<FoForm | null>(null)
  const [dirty, setDirty] = useState(false)
  const [navList, setNavList] = useState<FoSummary[]>([])
  const [finding, setFinding] = useState(false)
  const [focusKey, setFocusKey] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [confirm, setConfirm] = useState<null | { title: string; text: string; action: string; run: () => void }>(null)
  const workKey = `coop.foWork.${me.username}`
  const [recoverable, setRecoverable] = useState<null | { tab: string; form: FoForm; id: number | null; savedAt: string }>(() => {
    try { return JSON.parse(localStorage.getItem(`coop.foWork.${me.username}`) ?? 'null') } catch { return null }
  })

  const templates = useTemplates(me.username)
  const [receipt, setReceipt] = useState(false)
  // Live: a short poll of a cheap endpoint tells this screen when cash has been handed to this person or a supervisor is asked.
  const pulse = usePulse((p) => setNotice(`Cash from ${p.latestFrom} is waiting for you to count: ${money(p.latestAmount ?? 0)}`))

  const departments = useMemo(() => Object.entries(lk?.department ?? {}), [lk])
  const tabDef = setup?.tabs.find((t) => t.key === tab)
  const summary = tab === 'summary'
  const tabTypes = useMemo(() => (summary ? (setup?.tabs.flatMap((t) => t.types) ?? []) : tabDef?.types ?? []), [setup, tabDef, summary])
  const editing = shown.kind === 'draft'

  const txn = useQuery({
    queryKey: ['fo-txn', shown],
    queryFn: () => { const s = shown as Extract<Shown, { kind: 'txn' }>; return getFoTransaction(s.year, s.module, s.code, s.ctlNo) },
    enabled: shown.kind === 'txn',
  })

  // What the form shows: the draft being edited, a posted transaction, or an empty one.
  const view = useMemo(() => {
    const d = txn.data
    if (shown.kind !== 'txn' || !d) return null
    const f: FoForm = {
      trCode: d.header.code, date: d.header.date, batchNo: d.header.batchNo ?? '', dept: d.dept, clientId: d.header.clientId, clientName: d.header.clientName ?? '',
      refNo: d.header.refNo ?? '', explanation: d.header.explanation ?? '',
      lines: d.entries.map((e) => ({ key: `e${e.seq}`, accountCode: e.accountCode, accountDesc: e.description ?? '', acctNum: e.accountNo ?? '', debit: e.debit ? String(e.debit) : '', credit: e.credit ? String(e.credit) : '', dept: e.dept })),
      denoms: {},
    }
    const x: FoExtras = { ctl: String(d.header.ctlNo), time: d.header.time?.slice(0, 8) ?? '', cheques: d.cheques, allocations: d.allocations, cash: d.cash }
    return { f, x }
  }, [txn.data, shown.kind])
  const blank = useMemo(() => emptyForm(tabTypes[0]?.id ?? null, setup?.systemDate ?? ''), [tabTypes, setup?.systemDate])
  const shownForm = editing ? form ?? blank : view?.f ?? { ...blank, lines: [] }
  const extras: FoExtras = editing ? { ...NO_EXTRAS, ctl: shown.kind === 'draft' && shown.id ? `Draft ${shown.id}` : 'New draft', time: '' } : view?.x ?? NO_EXTRAS
  const totals = formTotals(shownForm.lines)
  const errorRow = problem ? Number(/Entry (\d+)/.exec(problem)?.[1]) || null : null
  const limit = tab === 'withdrawal' ? setup?.maxWithdrawal : ['disburse', 'otc', 'encashment'].includes(tab) ? setup?.disbursementLimit : null
  const overLimit = !!limit && limit > 0 && totals.debit > limit

  // ---- what the person can do -----------------------------------------------------------------------------------

  const tabOf = (code: number) => setup?.tabs.find((t) => t.types.some((x) => x.id === code))?.key ?? 'receipt'
  const guard = (then: () => void, text = 'The draft has changes that are not saved.') => {
    if (editing && dirty) setConfirm({ title: 'Discard changes?', text, action: 'Discard', run: then })
    else then()
  }
  const forgetWork = () => { try { localStorage.removeItem(workKey) } catch { /* storage unavailable */ } }
  const leaveDraft = () => { setShown({ kind: 'none' }); setForm(null); setDirty(false); setProblem(null); forgetWork() }

  const openTxn = (t: FoSummary, all: FoSummary[]) => {
    setFinding(false)
    guard(() => { leaveDraft(); setNavList(all); setTab(tabOf(t.code)); setShown({ kind: 'txn', year: t.year, module: t.module, code: t.code, ctlNo: t.ctlNo }) })
  }
  const openDraft = async (row: FoDraftRow) => {
    setFinding(false)
    try {
      const d = await getFoDraft(row.id)
      guard(() => { setTab(tabOf(d.payload.trCode)); setForm(fromPayload(d.payload)); setShown({ kind: 'draft', id: d.id }); setDirty(false); setProblem(null) })
    } catch (e) {
      setNotice((e as Error).message)
    }
  }

  const canNewHere = !summary && tabTypes.some((t) => t.canNew)
  const startNew = () => {
    if (!setup || !canNewHere) return
    guard(() => {
      const first = tabTypes.find((t) => t.canNew)
      setForm({ ...emptyForm(first?.id ?? null, setup.systemDate), dept: setup.defaultDept })
      setShown({ kind: 'draft', id: null })
      setFocusKey(null)
      setDirty(false)
      setProblem(null)
    })
  }

  const saveTemplate = (name: string) => {
    if (!form) return
    templates.save({ name, tab, form: { ...form, date: '', batchNo: '', refNo: '' } })
    setNotice(`Template “${name}” saved`)
  }
  const applyTemplate = (t: FoTemplate) => {
    if (!setup) return
    guard(() => {
      leaveDraft()
      setTab(t.tab)
      setForm({ ...t.form, date: setup.systemDate, batchNo: setup.systemDate.replace(/-/g, ''), refNo: '', lines: t.form.lines.map((l) => ({ ...l, key: newLine().key })) })
      setShown({ kind: 'draft', id: null })
      setDirty(true)
      setNotice(`Started from “${t.name}”. Check the amounts, then save.`)
    })
  }

  /** A repeat transaction is the commonest case: copy a posted one into a new draft for today. */
  const canCopy = shown.kind === 'txn' && !!view && !!setup && !!setup.tabs.flatMap((t) => t.types).find((t) => t.id === view.f.trCode)?.canNew
  const copyToDraft = () => {
    if (!view || !setup) return
    const f = view.f
    setTab(tabOf(f.trCode ?? 0))
    setForm({ ...f, date: setup.systemDate, batchNo: setup.systemDate.replace(/-/g, ''), refNo: '', denoms: {}, lines: f.lines.map((l) => ({ ...l, key: newLine().key })) })
    setShown({ kind: 'draft', id: null })
    setDirty(true)
    setProblem(null)
    setNotice('Copied into a new draft. Change what is different, then save.')
  }

  /** The side the member's account is on: a receipt credits it, a payout debits it. */
  const quickSide: 'credit' | 'debit' | null = tab === 'receipt' ? 'credit' : ['disburse', 'withdrawal', 'encashment', 'otc'].includes(tab) ? 'debit' : null
  const quickAdd = (a: FoClientAccount, amount: string) => {
    if (!form || !quickSide || !a.glCode) return
    const member = newLine()
    member.accountCode = a.glCode
    member.accountDesc = a.type
    member.acctNum = String(a.refNo)
    member.slc = a.slc
    member.slt = a.slt
    member.dept = form.dept
    const cash = newLine()
    cash.accountCode = setup?.cashAccount ?? '10003'
    cash.accountDesc = setup?.cashAccountDesc ?? 'Cash on hand - tellers'
    cash.dept = form.dept
    if (quickSide === 'credit') { member.credit = amount; cash.debit = amount } else { member.debit = amount; cash.credit = amount }
    const kept = form.lines.filter((x) => x.accountCode || num(x.debit) || num(x.credit))
    setForm({ ...form, lines: [...kept, member, cash] })
    setDirty(true)
  }

  /** Adds an entry for the difference so debits equal credits, and puts the cursor on it. */
  const balance = (cash: boolean) => {
    if (!form) return
    const { difference } = formTotals(form.lines)
    if (Math.abs(difference) < 0.005) return
    const l = newLine()
    const amount = Math.abs(difference).toFixed(2)
    if (difference > 0) l.credit = amount
    else l.debit = amount
    if (cash) { l.accountCode = setup?.cashAccount ?? '10003'; l.accountDesc = setup?.cashAccountDesc ?? 'Cash on hand - tellers' }
    setForm({ ...form, lines: [...form.lines.filter((x) => x.accountCode || num(x.debit) || num(x.credit)), l] })
    setFocusKey(l.key)
    setDirty(true)
  }

  const doSave = async () => {
    if (!form || !editing) return
    setSaving(true)
    setProblem(null)
    try {
      const payload = toPayload(form)
      const d = shown.kind === 'draft' && shown.id ? await updateFoDraft(shown.id, payload) : await createFoDraft(payload)
      setShown({ kind: 'draft', id: d.id })
      setDirty(false)
      forgetWork()
      setNotice(d.totals.balanced ? 'Draft saved' : 'Draft saved. Debits and credits do not match yet.')
      void qc.invalidateQueries({ queryKey: ['fo-drafts'] })
    } catch (e) {
      setProblem((e as Error).message)
    } finally {
      setSaving(false)
    }
  }

  /** Saves the draft, after checking that the same member and amount have not already been posted today. */
  const save = async () => {
    if (!form || !editing) return
    const amount = formTotals(form.lines).debit
    if (form.clientId != null && amount > 0) {
      try {
        const same = await getFoDuplicates(form.clientId, form.date, amount.toFixed(2))
        if (same.length > 0) {
          setConfirm({
            title: 'Possible duplicate',
            text: `${same.length === 1 ? 'A transaction' : `${same.length} transactions`} for ${form.clientName || 'this member'} of ${money(amount)} on ${form.date} ${same.length === 1 ? 'is' : 'are'} already posted: ${same.map((t) => `${t.type} no. ${t.ctlNo}`).join(', ')}. Save this draft anyway?`,
            action: 'Save anyway', run: () => { void doSave() },
          })
          return
        }
      } catch { /* the check is a courtesy; if it fails, saving carries on */ }
    }
    await doSave()
  }

  const removeDraft = () => {
    if (shown.kind !== 'draft' || !shown.id) return
    const id = shown.id
    setConfirm({
      title: 'Delete this draft?', text: 'It will be removed permanently. Posted transactions are not affected.', action: 'Delete',
      run: async () => {
        try {
          await deleteFoDraft(id)
          leaveDraft()
          setNotice('Draft deleted')
          void qc.invalidateQueries({ queryKey: ['fo-drafts'] })
        } catch (e) {
          setNotice((e as Error).message)
        }
      },
    })
  }

  const pos = shown.kind === 'txn' ? navList.findIndex((t) => t.year === shown.year && t.module === shown.module && t.code === shown.code && t.ctlNo === shown.ctlNo) : -1
  const step = (d: number) => {
    const next = navList[pos + d]
    if (next) setShown({ kind: 'txn', year: next.year, module: next.module, code: next.code, ctlNo: next.ctlNo })
  }
  const hasFind = tabTypes.some((t) => t.canFind)
  const cashTab = ['receipt', 'disburse', 'withdrawal', 'encashment', 'otc', 'sales'].includes(tab)

  const runs = {
    find: hasFind ? () => setFinding(true) : undefined,
    new: canNewHere ? startNew : undefined,
    save: editing && !saving && dirty ? save : undefined,
    cancel: editing && !saving ? () => guard(leaveDraft) : undefined,
    del: editing && shown.kind === 'draft' && shown.id ? removeDraft : undefined,
    copy: canCopy ? copyToDraft : undefined,
    balance: editing ? () => balance(false) : undefined,
    balanceCash: editing && cashTab ? () => balance(true) : undefined,
    up: shown.kind === 'txn' && pos > 0 ? () => step(-1) : undefined,
    down: shown.kind === 'txn' && pos >= 0 && pos < navList.length - 1 ? () => step(1) : undefined,
  }

  // Keep a working copy on this computer while a draft has unsaved changes, so a crash or a closed window loses nothing.
  useEffect(() => {
    if (!editing || !dirty || !form) return
    const t = setTimeout(() => {
      try { localStorage.setItem(workKey, JSON.stringify({ tab, form, id: shown.kind === 'draft' ? shown.id : null, savedAt: new Date().toISOString() })) } catch { /* storage unavailable */ }
    }, 800)
    return () => clearTimeout(t)
  }, [editing, dirty, form, tab, shown, workKey])

  // Keyboard: the same shortcuts as the menu, so a teller can work without the mouse.
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      if (confirm || finding) return
      const typing = /INPUT|TEXTAREA|SELECT/.test((e.target as HTMLElement).tagName)
      const key = e.key.toLowerCase()
      const hit = (fn?: () => void) => { if (fn) { e.preventDefault(); fn() } }
      if (mod(e) && key === 'f') hit(runs.find ?? (() => {}))
      else if (mod(e) && key === 'n') hit(runs.new)
      else if (mod(e) && key === 's') hit(runs.save)
      else if (mod(e) && key === 'd') hit(runs.copy)
      else if (mod(e) && e.shiftKey && key === 'b') hit(runs.balanceCash)
      else if (mod(e) && key === 'b') hit(runs.balance)
      else if (mod(e) && e.key === 'Delete') hit(runs.del)
      else if (mod(e) && !e.shiftKey && !e.altKey && /^[1-9]$/.test(e.key) && setup?.tabs[Number(e.key) - 1]) {
        const target = setup.tabs[Number(e.key) - 1].key
        e.preventDefault()
        guard(() => { leaveDraft(); setTab(target) })
      }
      else if (e.key === 'Escape' && !typing) hit(runs.cancel)
      else if (e.key === 'PageUp' && !typing) hit(runs.up)
      else if (e.key === 'PageDown' && !typing) hit(runs.down)
    }
    window.addEventListener('keydown', on)
    return () => window.removeEventListener('keydown', on)
  })

  const items: MenuEntry[] = [
    { label: 'Find', icon: <FiSearch />, shortcut: `${MOD}F`, run: runs.find },
    { label: 'New draft', icon: <FiPlus />, shortcut: `${MOD}N`, run: runs.new },
    { label: 'Copy to new draft', icon: <FiCopy />, shortcut: `${MOD}D`, run: runs.copy },
    { label: 'Save draft', icon: <FiSave />, shortcut: `${MOD}S`, run: runs.save, divider: true },
    { label: 'Balance', icon: <FiPlus />, shortcut: `${MOD}B`, run: runs.balance },
    { label: 'Balance with cash', icon: <FiPlus />, shortcut: `${MOD}Shift+B`, run: runs.balanceCash },
    { label: 'Cancel', icon: <FiX />, shortcut: 'Esc', run: runs.cancel },
    { label: 'Delete draft', icon: <FiTrash2 />, shortcut: `${MOD}Del`, run: runs.del },
    { label: 'Page up', icon: <FiChevronUp />, shortcut: 'PgUp', run: runs.up, divider: true },
    { label: 'Page down', icon: <FiChevronDown />, shortcut: 'PgDn', run: runs.down },
  ]

  // ---- screens ---------------------------------------------------------------------------------------------------

  if (error) return <Alert severity="error" sx={{ m: 2 }}>{(error as Error).message}</Alert>
  if (isLoading || !setup) return <Box sx={{ p: 2 }}><Skeleton variant="rounded" height={420} /></Box>
  if (!setup.canAccess) {
    return <Alert severity="info" sx={{ m: 2 }}>Your account does not have access to the front office. Ask an administrator to turn it on.</Alert>
  }

  return (
    <Box sx={(t) => ({ height: 'calc(100dvh - 44px)', display: 'flex', flexDirection: 'column', gap: 1.25, p: 1.5, background: appBackground(t), overflow: 'hidden' })}>
      <ToolbarPortal><ToolbarMenu label="Front Office" mnemonic="f" items={items} /></ToolbarPortal>

      <FoFindDialog open={finding} onClose={() => setFinding(false)} setup={setup} typeIds={tabTypes.filter((t) => t.canFind).map((t) => t.id)} onPickTransaction={openTxn} onPickDraft={openDraft} />

      <Dialog open={!!confirm} onClose={() => setConfirm(null)} aria-labelledby="fo-confirm-title">
        <DialogTitle id="fo-confirm-title">{confirm?.title}</DialogTitle>
        <DialogContent><DialogContentText>{confirm?.text}</DialogContentText></DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirm(null)} autoFocus>Keep</Button>
          <Button color="error" onClick={() => { const run = confirm?.run; setConfirm(null); run?.() }}>{confirm?.action}</Button>
        </DialogActions>
      </Dialog>
      <Snackbar open={!!notice} autoHideDuration={3500} onClose={() => setNotice(null)} message={notice} />
      <ReceiptDialog detail={txn.data} user={me.username} open={receipt} onClose={() => setReceipt(false)} />

      {/* Tabs, as in the old window */}
      <Tabs
        value={tab} onChange={(_, v) => guard(() => { leaveDraft(); setTab(v) })} variant="scrollable" scrollButtons="auto" aria-label="Transaction kinds"
        sx={(t) => ({ minHeight: 40, px: 1, flexShrink: 0, ...glass(t), '& .MuiTab-root': { minHeight: 40, fontSize: 13.5, px: 1.5 } })}
      >
        {setup.tabs.map((t, i) => {
          // A badge shows what needs attention: cash to count (or a count that differs) on Cash Handover, requests to approve on Summary.
          const waiting = t.key === 'handover' ? (pulse?.incoming ?? 0) + (pulse?.disputed ?? 0) : t.key === 'summary' ? (pulse?.overrides ?? 0) : 0
          return (
            <Tab
              key={t.key} value={t.key} title={i < 9 ? `${MOD}${i + 1}` : undefined}
              label={waiting ? <Badge color="warning" badgeContent={waiting} sx={{ '& .MuiBadge-badge': { right: -14 } }}>{t.label}</Badge> : t.label}
            />
          )
        })}
      </Tabs>

      {/* Actions, and who is working on what date */}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, flexWrap: 'wrap', flexShrink: 0 }} role="toolbar" aria-label="Transaction actions">
        <Button size="small" startIcon={<FiSearch />} disabled={!runs.find} onClick={runs.find}>Find</Button>
        <Button size="small" startIcon={<FiPlus />} disabled={!runs.new} onClick={runs.new}>New</Button>
        <Button size="small" startIcon={<FiCopy />} disabled={!runs.copy} onClick={runs.copy}>Copy</Button>
        <TemplatesMenu templates={templates} canSave={editing && !!form} onUse={applyTemplate} onSave={saveTemplate} />
        <Button size="small" startIcon={<FiPrinter />} disabled={!(shown.kind === 'txn' && txn.data)} onClick={() => setReceipt(true)}>Receipt</Button>
        <Button size="small" startIcon={<FiSave />} variant={editing ? 'contained' : 'text'} disabled={!runs.save} onClick={runs.save}>{saving ? 'Saving…' : 'Save'}</Button>
        <Button size="small" startIcon={<FiX />} disabled={!runs.cancel} onClick={runs.cancel}>Cancel</Button>
        <Button size="small" startIcon={<FiChevronUp />} disabled={!runs.up} onClick={runs.up}>Page Up</Button>
        <Button size="small" startIcon={<FiChevronDown />} disabled={!runs.down} onClick={runs.down}>Page Down</Button>
        <Box sx={{ flex: 1 }} />
        <Typography sx={{ fontSize: 13.5 }}>User: <b>{me.username}</b></Typography>
        <Typography sx={{ fontSize: 13.5, ml: 2 }}>System date: <b style={{ fontVariantNumeric: 'tabular-nums' }}>{setup.systemDate}</b></Typography>
      </Box>

      {editing && (
        <Box sx={(t) => ({ display: 'flex', alignItems: 'center', gap: 1.5, px: 1.5, py: 0.75, flexShrink: 0, ...glass(t) })} role="status">
          <FiEdit2 aria-hidden />
          <Typography sx={{ flex: 1, fontSize: 13.5 }}>
            Draft. Nothing is posted to the ledger, and posting is not available yet.
            {dirty ? ' Changes not saved.' : ''}
            {overLimit && <b style={{ marginLeft: 8, color: 'inherit' }}>Over your limit of {money(limit ?? 0)}.</b>}
          </Typography>
          <Chip size="small" variant="outlined" color={Math.abs(totals.difference) < 0.005 && totals.debit > 0 ? 'success' : 'warning'}
            label={Math.abs(totals.difference) < 0.005 && totals.debit > 0 ? 'Balanced' : 'Out of balance'} />
          {overLimit && (shown.kind === 'draft' && shown.id
            ? <OverrideAsk draftId={shown.id} amount={totals.debit} limit={limit ?? 0} summary={`${form?.clientName || 'Draft'}: ${money(totals.debit)} over a limit of ${money(limit ?? 0)}`} />
            : <Typography sx={{ fontSize: 12.5 }} color="text.secondary">Save the draft to ask a supervisor.</Typography>)}
        </Box>
      )}
      {recoverable && !editing && (
        <Alert
          severity="info" sx={{ flexShrink: 0 }}
          action={(
            <>
              <Button color="inherit" size="small" onClick={() => { setTab(recoverable.tab); setForm(recoverable.form); setShown({ kind: 'draft', id: recoverable.id }); setDirty(true); setRecoverable(null) }}>Restore</Button>
              <Button color="inherit" size="small" onClick={() => { forgetWork(); setRecoverable(null) }}>Discard</Button>
            </>
          )}
        >
          You have unsaved work from {new Date(recoverable.savedAt).toLocaleString()}.
        </Alert>
      )}
      {problem && <Alert severity="error" sx={{ flexShrink: 0 }}>{problem}</Alert>}

      {summary
        ? <SummaryPanel setup={setup} onOpen={openTxn} />
        : tab === 'handover' && shown.kind === 'none'
          ? <CashHandoverPanel setup={setup} onNotice={setNotice} />
          : tab === 'disburse' && shown.kind === 'none'
          ? <><LoanApplicationsPanel /><LoanReleasePanel /></>
          : tab === 'passbook'
          ? <Typography sx={{ p: 4, textAlign: 'center' }} color="text.secondary">Passbook printing is coming soon.</Typography>
          : txn.isLoading && shown.kind === 'txn'
            ? <Skeleton variant="rounded" sx={{ flex: 1 }} />
            : txn.error
              ? <Alert severity="error">{(txn.error as Error).message}</Alert>
              : (
                <FoTransactionForm
                  setup={setup} types={tabTypes} form={shownForm} extras={extras} editing={editing} departments={departments as [string, string][]}
                  onChange={(patch) => { setForm((f) => ({ ...(f ?? blank), ...patch })); setDirty(true) }}
                  focusKey={focusKey} onFocusLine={setFocusKey} errorRow={errorRow} onBalance={editing ? balance : undefined} cashBalance={cashTab}
                  quickSide={quickSide} onQuickAdd={quickAdd}
                />
              )}
      {!summary && tab !== 'passbook' && tab !== 'handover' && shown.kind === 'none' && (
        <Typography sx={{ fontSize: 12.5, textAlign: 'center', flexShrink: 0 }} color="text.secondary">
          {hasFind ? `Press ${MOD}F to find a transaction` : ''}{hasFind && canNewHere ? ' or ' : ''}{canNewHere ? `${MOD}N to start a draft.` : ''}
        </Typography>
      )}
    </Box>
  )
}
