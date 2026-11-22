import { Alert, Box, Button, Divider, IconButton, MenuItem, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { FiChevronDown, FiChevronUp, FiFilePlus, FiPlus, FiSave, FiSearch, FiTrash2, FiX } from 'react-icons/fi'
import {
  approveWaitingVoucher, checkFoDraftPost, createFoDraft, deleteFoDraft, getBankCashBanks, getMember, getVoucherSetup, getWaitingVoucher, getWaitingVouchers,
  listBackOfficeTypes, postFoDraft, postWaitingVoucher, searchPostingAccounts, type BackOfficeType, type DraftPostIn, type FoDraftPayload, type WaitingVoucher,
} from './api'
import { DiscardDialog, EntryList, FindDialog, NotMadeHere, Panel, ReadOnly, ToolButton, Toolbar, Totals, TypePicker, type Entry } from './BackOfficeParts'
import { EntryStepStatus } from './EntryStepStatus'
import { useLeaveGuard } from './leaveGuard'
import MoneyField from './MoneyField'
import { fmtDate } from './profile/format'
import { useMe } from './session'
import { AccountPicker } from './ui/AccountPicker'
import { ConfirmButton } from './ui/dialogs'
import { money } from './ui/format'
import { Gated } from './ui/Gated'
import { useRequestKey } from './ui/requestKey'
import { usePageKeys } from './ui/usePageKeys'

/**
 * The Disbursement Transactions window (pics/disbursement.png): the old system's module 2 types in the Trans. type list, read from the server. A Cash
 * Voucher (21) or a Check Voucher (22) is made here: the client, the payee on the cheque (And/Or), the debits; the window adds the credit itself (the
 * payee's vouchers payable, or the bank the cheque is drawn on). It is saved through the front office draft and its posting, so every check there
 * applies, and it then waits: another person approves it, a third posts it (documentation/general-voucher-map.md). The list is the vouchers waiting.
 */

type Kind = 'CV' | 'CKV'
const CODE: Record<Kind, 21 | 22> = { CV: 21, CKV: 22 }
const LABEL: Record<Kind, string> = { CV: 'cash voucher', CKV: 'check voucher' }
const FALLBACK: BackOfficeType[] = [
  { id: 21, name: 'Cash Voucher', code: 'CV', entries: 0, lastUsed: null, entry: 'CV', madeElsewhere: null },
  { id: 22, name: 'Check Voucher', code: 'CKV', entries: 0, lastUsed: null, entry: 'CKV', madeElsewhere: null },
]
const STATUS: Record<WaitingVoucher['status'], string> = { 'WAITING FOR APPROVAL': 'FOR_REVIEW', APPROVED: 'APPROVED', POSTED: 'POSTED' }
const today = () => new Date().toLocaleDateString('en-CA')
const dateText = (d: string) => fmtDate(d) ?? d
const keyOf = (v: { year: number; controlNo: number }) => v.year * 100_000_000 + v.controlNo

const asEntry = (v: WaitingVoucher): Entry => ({
  id: keyOf(v), reference: v.documentNo || String(v.controlNo), date: v.date, description: [v.clientName, v.explanation].filter(Boolean).join(' · '),
  status: STATUS[v.status] ?? v.status, createdBy: v.encodedByName ?? String(v.encodedBy), approvedBy: v.approvedByName, postedBy: null, total: v.amount, reversal: false, documentNo: v.documentNo,
})

export function BackOfficeDisbursement() {
  const [choice, setChoice] = useState('21')
  const typesQ = useQuery({ queryKey: ['backoffice-types', 2], queryFn: () => listBackOfficeTypes(2), staleTime: 10 * 60_000, retry: false })
  const types = typesQ.data && typesQ.data.length > 0 ? typesQ.data : FALLBACK
  const old = types.find((t) => String(t.id) === choice) ?? null
  const kind: Kind | null = old?.entry === 'CV' || old?.entry === 'CKV' ? old.entry : null
  const setup = useQuery({ queryKey: ['voucher-setup'], queryFn: getVoucherSetup, staleTime: 5 * 60_000, retry: false })
  const list = useQuery({ queryKey: ['vouchers-waiting'], queryFn: getWaitingVouchers, enabled: kind !== null, retry: false })
  const vouchers = (list.data ?? []).filter((v) => kind && v.code === CODE[kind])
  const entries = vouchers.map(asEntry)
  const [openId, setOpenId] = useState<number | null>(null)
  const [making, setMaking] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [finding, setFinding] = useState(false)
  const at = openId === null ? -1 : entries.findIndex((e) => e.id === openId)
  const shown = at >= 0 ? vouchers[at] : null
  const may = kind === 'CV' ? !!setup.data?.mayCashVoucher : kind === 'CKV' ? !!setup.data?.mayCheckVoucher : false

  useLeaveGuard(making && dirty ? 'The new voucher has not been saved.' : null)
  const [discarding, setDiscarding] = useState<(() => void) | null>(null)
  const leaveNew = (move: () => void) => { if (making && dirty) setDiscarding(() => move); else move() }
  const choose = (c: string) => { if (c !== choice) leaveNew(() => { setChoice(c); setOpenId(null); setMaking(false); setDirty(false) }) }
  const open = (id: number) => leaveNew(() => { setMaking(false); setDirty(false); setOpenId(id) })
  const startNew = () => leaveNew(() => { setOpenId(null); setDirty(false); setMaking(true) })
  const step = (d: -1 | 1) => {
    if (entries.length === 0) return
    const next = at < 0 ? (d === 1 ? 0 : entries.length - 1) : at + d
    if (next >= 0 && next < entries.length) open(entries[next].id)
  }
  usePageKeys(kind !== null && !making, step)

  return (
    <>
      {kind && (
        <Toolbar>
          <ToolButton icon={<FiSearch />} label="Find" onClick={() => setFinding(true)} />
          <Gated allowed={may || making} reason={setup.data?.problem ?? `You need New on ${old?.name ?? 'this type'} (Settings > Users > Transactions).`}>
            <ToolButton icon={<FiFilePlus />} label="New" disabled={making || !setup.data} onClick={startNew} />
          </Gated>
          <ToolButton icon={<FiSave />} label="Save" form="disbursement-entry" type="submit" disabled={!making} />
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
            <TypePicker types={types} value={choice} onChange={choose} />
            {kind && <ReadOnly label="Control No." value={making ? 'Assigned when saved' : shown ? shown.documentNo || String(shown.controlNo) : '—'} />}
            {kind && <ReadOnly label="Dept" value={setup.data?.dept != null ? String(setup.data.dept) : '—'} />}
            {kind && shown && !making && <EntryStepStatus status={STATUS[shown.status] ?? shown.status} />}
          </Stack>
        </Panel>
        {kind && !making && shown && (
          <Panel sx={{ flex: 2 }}>
            <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
              <ReadOnly label="Trans. date" value={dateText(shown.date)} />
              <ReadOnly label="Encoded by" value={shown.encodedByName ?? String(shown.encodedBy)} />
              {shown.approvedByName && <ReadOnly label="Approved by" value={shown.approvedByName} />}
            </Stack>
          </Panel>
        )}
      </Stack>

      {kind && setup.data?.problem && <Alert severity="warning" sx={{ mb: 1.5 }}>{setup.data.problem}</Alert>}
      {kind === null && old && <NotMadeHere type={old} otherwise="Use a Cash Voucher or a Check Voucher where one fits." />}
      {kind && making && setup.data && (
        <NewVoucher key={kind} kind={kind} setup={setup.data} onDirty={setDirty}
          onSaved={(v) => { setMaking(false); setDirty(false); void list.refetch().then(() => setOpenId(keyOf(v))) }} />
      )}
      {kind && !making && shown && <VoucherView key={keyOf(shown)} voucher={shown} onChanged={() => void list.refetch()} />}
      {kind && !making && !shown && (
        <Panel>
          <Typography sx={{ fontSize: 13, mb: 1.5 }} color="text.secondary">
            Press New to make a {LABEL[kind]}, or open one waiting below (Find searches them all). Posted vouchers leave this list; they are under Front Office.
          </Typography>
          <EntryList entries={entries.slice(0, 12)} loading={list.isLoading} error={list.error as Error | null} onOpen={open} empty={`No ${LABEL[kind]} is waiting for approval or posting.`} />
        </Panel>
      )}
      {finding && kind && <FindDialog entries={entries} loading={list.isLoading} error={list.error as Error | null} typeLabel={`${LABEL[kind]} waiting`}
        onClose={() => setFinding(false)} onOpen={(id) => { setFinding(false); open(id) }} />}
      {discarding && <DiscardDialog onKeep={() => setDiscarding(null)} onDiscard={() => { const move = discarding; setDiscarding(null); move() }} />}
    </>
  )
}

// ---- making a voucher -----------------------------------------------------------------------------------------------

interface Line { account: string; accountName: string; debit: string }
const blank = (): Line => ({ account: '', accountName: '', debit: '' })

function NewVoucher({ kind, setup, onDirty, onSaved }: { kind: Kind; setup: NonNullable<Awaited<ReturnType<typeof getVoucherSetup>>>; onDirty: (d: boolean) => void; onSaved: (v: { year: number; controlNo: number }) => void }) {
  const me = useMe()
  const requestKey = useRequestKey()
  const check = kind === 'CKV'
  const [client, setClient] = useState('')
  const [looked, setLooked] = useState('')
  const [payee, setPayee] = useState('')
  const [refNo, setRefNo] = useState('')
  const [date, setDate] = useState(today())
  const [explanation, setExplanation] = useState('')
  const [lines, setLines] = useState<Line[]>([blank()])
  const [bank, setBank] = useState('')
  const [chequeNo, setChequeNo] = useState('')
  const [chequeDate, setChequeDate] = useState(today())
  const touch = () => onDirty(true)
  const set = (i: number, patch: Partial<Line>) => { touch(); setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...patch } : l))) }

  const clientId = Number(looked)
  const member = useQuery({ queryKey: ['member', me.branchCode ?? 1, clientId], queryFn: () => getMember(me.branchCode ?? 1, clientId), enabled: Number.isInteger(clientId) && clientId > 0, retry: false })
  const clientName = member.data ? member.data.accountName ?? [member.data.lastName, member.data.firstName].filter(Boolean).join(', ') : ''
  const banks = useQuery({ queryKey: ['bank-cash-banks'], queryFn: getBankCashBanks, staleTime: 10 * 60_000, enabled: check, retry: false })
  const chosen = (banks.data ?? []).find((b) => `${b.bankId}/${b.ledgerAccount}` === bank) ?? null
  const look = () => {
    const c = client.trim()
    if (c === looked) return
    setLooked(c)
  }

  const total = Math.round(lines.reduce((s, l) => s + (Number(l.debit) || 0), 0) * 100) / 100
  const credit = check ? (chosen ? { account: chosen.ledgerAccount, name: chosen.name } : null) : setup.payableAccount ? { account: setup.payableAccount, name: setup.payableAccountName ?? 'Vouchers payable' } : null
  const missing = [
    !check && !member.data ? 'the member (the payee, whose vouchers payable it credits)' : null,
    check && !payee.trim() ? 'the And/Or (the payee on the cheque)' : null,
    check && !chosen ? 'the bank account the cheque is drawn on' : null,
    check && !/^\d{1,15}$/.test(chequeNo.trim()) ? 'the cheque number (digits, up to 15)' : null,
    !lines.every((l) => l.account && Number(l.debit) > 0) ? 'every debit entry (an account and an amount)' : null,
    explanation.trim().length < 3 ? 'the description' : null,
  ].filter(Boolean) as string[]

  const save = useMutation({
    meta: { done: (r) => `${check ? 'Check' : 'Cash'} Voucher ${(r as { documentNo: string }).documentNo} encoded. It waits for another person to approve it.` },
    mutationFn: async () => {
      if (!credit) throw new Error(check ? 'Choose the bank account.' : setup.problem ?? 'There is no vouchers-payable account to credit.')
      const dept = setup.dept
      const payload: FoDraftPayload = {
        trCode: CODE[kind], date, batchNo: date.replaceAll('-', ''), dept, clientId: member.data ? clientId : null, clientName: clientName || payee.trim(), refNo: refNo.trim(),
        explanation: explanation.trim(), denominations: [],
        lines: [
          ...lines.map((l) => ({ accountCode: l.account, accountDesc: l.accountName, acctNum: '', debit: (Number(l.debit) || 0).toFixed(2), credit: '0', dept, slc: null, slt: null })),
          { accountCode: credit.account, accountDesc: credit.name, acctNum: '', debit: '0', credit: total.toFixed(2), dept, slc: null, slt: null },
        ],
      }
      const body: DraftPostIn = { cheque: check ? { bankId: chosen?.bankId ?? null, account: chosen?.ledgerAccount ?? null, number: chequeNo.trim(), date: chequeDate, payee: payee.trim() } : null }
      const draft = await createFoDraft(payload)
      try {
        const plan = await checkFoDraftPost(draft.id, body)
        if (!plan.ready) throw new Error(plan.problems.join(' ') || 'It cannot be encoded yet.')
        return await postFoDraft(draft.id, body, requestKey)
      } catch (e) {
        // the draft was only a way in: nothing is left behind in the front office drafts when the voucher could not be made
        await deleteFoDraft(draft.id).catch(() => undefined)
        throw e
      }
    },
    onSuccess: (r) => onSaved({ year: r.year, controlNo: r.controlNo }),
  })

  return (
    <Box component="form" id="disbursement-entry" onSubmit={(e) => { e.preventDefault(); if (missing.length === 0 && !save.isPending) save.mutate() }}>
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} sx={{ mb: 1.5 }}>
        <Panel sx={{ flex: 3 }}>
          <Stack spacing={1.5}>
            <Stack direction="row" spacing={1.5}>
              <TextField size="small" label="Member ID" value={client} required={!check} onChange={(e) => { touch(); setClient(e.target.value.replace(/\D/g, '')) }} onBlur={look}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); look() } }} sx={{ width: 140 }}
                error={member.isError} helperText={member.isError ? 'No such member' : undefined} />
              <TextField size="small" label="Member" value={clientName} slotProps={{ input: { readOnly: true } }} sx={{ flex: 1 }} />
            </Stack>
            {check && (
              <TextField size="small" label="And/Or (payee on the cheque)" required value={payee} onChange={(e) => { touch(); setPayee(e.target.value) }}
                onFocus={() => { if (!payee && clientName) setPayee(clientName) }} slotProps={{ htmlInput: { maxLength: 60 } }} />
            )}
          </Stack>
        </Panel>
        <Panel sx={{ flex: 2 }}>
          <Stack spacing={1.5}>
            <TextField size="small" label="Ref. Number" value={refNo} onChange={(e) => { touch(); setRefNo(e.target.value) }} slotProps={{ htmlInput: { maxLength: 40 } }} />
            <Stack direction="row" spacing={1.5}>
              <TextField size="small" type="date" label="Trans. date" value={date} onChange={(e) => { touch(); setDate(e.target.value) }} slotProps={{ inputLabel: { shrink: true } }} sx={{ flex: 1 }} />
              <ReadOnly label="Batch No." value={date.replaceAll('-', '')} />
            </Stack>
          </Stack>
        </Panel>
      </Stack>

      {check && (
        <Panel sx={{ mb: 1.5 }}>
          <Typography component="h2" sx={{ fontSize: 13, fontWeight: 700, mb: 1 }}>The cooperative&rsquo;s cheque</Typography>
          <Stack direction="row" spacing={1.5} sx={{ flexWrap: 'wrap', rowGap: 1.5 }}>
            <TextField select size="small" label="Bank account" required value={bank} onChange={(e) => { touch(); setBank(e.target.value) }} sx={{ minWidth: 280 }}
              error={banks.isError} helperText={banks.isError ? (banks.error as Error).message : undefined}>
              {(banks.data ?? []).map((b) => <MenuItem key={`${b.bankId}/${b.ledgerAccount}`} value={`${b.bankId}/${b.ledgerAccount}`}>{b.name} · {b.ledgerAccount}</MenuItem>)}
            </TextField>
            <TextField size="small" label="Cheque number" required value={chequeNo} onChange={(e) => { touch(); setChequeNo(e.target.value.replace(/\D/g, '').slice(0, 15)) }} />
            <TextField size="small" type="date" label="Cheque date" value={chequeDate} onChange={(e) => { touch(); setChequeDate(e.target.value) }} slotProps={{ inputLabel: { shrink: true } }} />
          </Stack>
        </Panel>
      )}

      <Panel sx={{ mb: 1.5 }}>
        <Typography component="h2" sx={{ fontSize: 13, fontWeight: 700, mb: 1 }}>GL/SL Entries</Typography>
        <Table size="small" aria-label="GL/SL entries">
          <TableHead>
            <TableRow>
              <TableCell sx={{ width: 32 }}>#</TableCell><TableCell>Account</TableCell>
              <TableCell align="right" sx={{ width: 160 }}>Debit</TableCell><TableCell align="right" sx={{ width: 160 }}>Credit</TableCell><TableCell sx={{ width: 44 }} />
            </TableRow>
          </TableHead>
          <TableBody>
            {lines.map((l, i) => (
              <TableRow key={i}>
                <TableCell>{i + 1}</TableCell>
                <TableCell>
                  <AccountPicker code={l.account} desc={l.accountName} label={`Entry ${i + 1} account`} width={340} placeholder="Account number or name"
                    search={(q) => searchPostingAccounts(me.branchCode ?? 1, q)} recentKey="coop.voucherRecentAccounts"
                    onPick={(a) => set(i, { account: a?.code ?? '', accountName: a?.description ?? '' })} />
                </TableCell>
                <TableCell><MoneyField size="small" label="Debit" value={l.debit} onValueChange={(v) => set(i, { debit: v })} fullWidth /></TableCell>
                <TableCell />
                <TableCell><IconButton aria-label={`Remove entry ${i + 1}`} disabled={lines.length <= 1} onClick={() => { touch(); setLines((ls) => ls.filter((_, j) => j !== i)) }}><FiTrash2 /></IconButton></TableCell>
              </TableRow>
            ))}
            <TableRow>
              <TableCell>{lines.length + 1}</TableCell>
              <TableCell>
                {credit
                  ? <>{credit.account} <Typography component="span" sx={{ fontSize: 12, ml: 1 }} color="text.secondary">{credit.name} · {check ? 'the bank the cheque is drawn on' : 'the payee’s vouchers payable'}, added by the window</Typography></>
                  : <Typography component="span" sx={{ fontSize: 12 }} color="text.secondary">{check ? 'Choose the bank account: its ledger account is credited.' : setup.problem ?? 'No vouchers-payable account is set up.'}</Typography>}
              </TableCell>
              <TableCell />
              <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{credit ? money(total) : ''}</TableCell>
              <TableCell />
            </TableRow>
          </TableBody>
        </Table>
        <Button size="small" startIcon={<FiPlus />} sx={{ mt: 1 }} onClick={() => { touch(); setLines((ls) => [...ls, blank()]) }}>Add debit</Button>
        <Totals debit={total} credit={credit ? total : 0} />
      </Panel>

      <Panel>
        <TextField fullWidth multiline minRows={3} size="small" label="Description" required value={explanation} onChange={(e) => { touch(); setExplanation(e.target.value) }} slotProps={{ htmlInput: { maxLength: 2000 } }} />
        {missing.length > 0 && <Typography sx={{ fontSize: 12, mt: 1 }} color="text.secondary">To save, fill in {missing.join(', ')}.</Typography>}
        {save.error && <Alert severity="error" sx={{ mt: 1 }}>{(save.error as Error).message}</Alert>}
        <Stack direction="row" sx={{ mt: 1.5, justifyContent: 'flex-end' }}>
          <Button type="submit" variant="contained" startIcon={<FiSave />} disabled={missing.length > 0 || save.isPending || !credit}>Save and send for approval</Button>
        </Stack>
      </Panel>
    </Box>
  )
}

// ---- one voucher waiting --------------------------------------------------------------------------------------------

function VoucherView({ voucher, onChanged }: { voucher: WaitingVoucher; onChanged: () => void }) {
  const qc = useQueryClient()
  const id = [voucher.code, voucher.year, voucher.controlNo] as const
  const detail = useQuery({ queryKey: ['waiting-voucher', ...id], queryFn: () => getWaitingVoucher(...id) })
  const done = () => { void qc.invalidateQueries({ queryKey: ['waiting-voucher', ...id] }); onChanged() }
  const approve = useMutation({ meta: { done: 'Approved. A third person can now post it.' }, mutationFn: () => approveWaitingVoucher(...id), onSuccess: done })
  const post = useMutation({ meta: { done: `${voucher.type} ${voucher.documentNo} posted.` }, mutationFn: () => postWaitingVoucher(...id), onSuccess: done })
  const d = detail.data
  const lines = d?.lines ?? []
  const dr = lines.reduce((t, l) => t + Math.max(l.amount, 0), 0)
  const cr = lines.reduce((t, l) => t + Math.max(-l.amount, 0), 0)
  const why = d && d.problems.length > 0 ? d.problems.join(' ') : null
  const error = (approve.error ?? post.error) as Error | null
  return (
    <>
      <Panel sx={{ mb: 1.5 }}>
        <Stack direction="row" spacing={3} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
          <ReadOnly label="Member" value={voucher.clientId ? `${voucher.clientId} · ${voucher.clientName ?? ''}` : '—'} />
          {voucher.cheque && <ReadOnly label="And/Or (payee)" value={voucher.cheque.payee} />}
          {voucher.cheque && <ReadOnly label="Cheque" value={`${voucher.cheque.bank} · No. ${voucher.cheque.number} · ${dateText(voucher.cheque.date)}`} />}
        </Stack>
      </Panel>
      <Panel sx={{ mb: 1.5 }}>
        <Typography component="h2" sx={{ fontSize: 13, fontWeight: 700, mb: 1 }}>GL/SL Entries</Typography>
        <Table size="small" aria-label="GL/SL entries">
          <TableHead><TableRow><TableCell sx={{ width: 32 }}>#</TableCell><TableCell>Account</TableCell><TableCell>Account no.</TableCell><TableCell align="right">Debit</TableCell><TableCell align="right">Credit</TableCell></TableRow></TableHead>
          <TableBody>
            {detail.isLoading && <TableRow><TableCell colSpan={5}>Loading…</TableCell></TableRow>}
            {detail.error && <TableRow><TableCell colSpan={5}><Alert severity="error">{(detail.error as Error).message}</Alert></TableCell></TableRow>}
            {lines.map((l, i) => (
              <TableRow key={l.seq}>
                <TableCell>{i + 1}</TableCell>
                <TableCell>{l.account}<Typography component="span" sx={{ fontSize: 12, ml: 1 }} color="text.secondary">{l.description}</Typography></TableCell>
                <TableCell>{l.refNo ?? ''}</TableCell>
                <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{l.amount > 0 ? money(l.amount) : ''}</TableCell>
                <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{l.amount < 0 ? money(-l.amount) : ''}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {d && <Totals debit={dr} credit={cr} />}
      </Panel>
      <Panel>
        <Typography sx={{ fontSize: 11 }} color="text.secondary">Description</Typography>
        <Typography sx={{ fontSize: 13, whiteSpace: 'pre-wrap', mb: 1.5 }}>{voucher.explanation || '—'}</Typography>
        {error && <Alert severity="error" sx={{ mb: 1.5 }}>{error.message}</Alert>}
        <Stack direction="row" spacing={1} sx={{ justifyContent: 'flex-end' }}>
          {voucher.status === 'WAITING FOR APPROVAL' && (
            <Gated allowed={!!d?.mayApprove} reason={why ?? 'Someone other than the person who encoded it approves it, with New on this type.'}>
              <Button variant="contained" disabled={approve.isPending || !d} onClick={() => approve.mutate()}>Approve</Button>
            </Gated>
          )}
          {voucher.status === 'APPROVED' && (
            <Gated allowed={!!d?.mayPost} reason={why ?? 'A third person, who neither encoded nor approved it, posts it.'}>
              <ConfirmButton variant="contained" disabled={post.isPending || !d} onConfirm={() => post.mutate()} title={`Post ${voucher.type} ${voucher.documentNo}?`}
                message={<>It writes <b>{money(voucher.amount)}</b> to the ledger{voucher.cheque ? <>, paid by cheque No. {voucher.cheque.number} to {voucher.cheque.payee}</> : <>, to {voucher.clientName ?? 'the payee'}&rsquo;s vouchers payable</>}, dated {dateText(voucher.date)}. A posted voucher cannot be edited.</>}>Post</ConfirmButton>
            </Gated>
          )}
        </Stack>
      </Panel>
    </>
  )
}
