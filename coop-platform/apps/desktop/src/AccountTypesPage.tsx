import {
  Alert, Autocomplete, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, List, ListItemButton, ListSubheader, MenuItem, Stack, Switch,
  Table, TableBody, TableCell, TableHead, TableRow, TextField, Tooltip, Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { FiPlus, FiTrash2 } from 'react-icons/fi'
import {
  addAccountTypeLink, addCashDrawer, getCashSetup, setUpCash, changeAccountTypeLink, createAccountType, createFirstAccountType, getAccountTypeLinkOptions, getAccountTypeLinks, getAccountTypes, getCashDrawers,
  checkAccountTypeDelete, deleteAccountType, getChart, getLinkSuggestions, removeAccountTypeLink, updateAccountType,
  type AccountType, type AccountTypeClass, type AccountTypeForm, type AccountTypeLink, type ChartAccount,
} from './api'
import { AboutNote, Dot, Hinted, listTable, PageTitle, ReasonDialog, SectionLabel, Surface } from './appleUi'
import { appBackground } from './glass'
import { useLeaveGuard } from './leaveGuard'
import { AccountPicker } from './ui/AccountPicker'
import { Loading } from './ui/Loading'
import { notifyError } from './ui/notices'
import { money, tabular } from './ui/format'

type Fields = { name: string; fullName: string; priority: string; interestRate: string; minBalance: string; maxBalance: string; dormantAfterMonths: string; disabled: boolean }

const text = (n: number | null) => (n == null ? '' : String(n))
const number = (s: string) => (s.trim() === '' ? null : Number(s))
/** A longer name kept from the old system, when it differs from the name; empty means the name is stored in both columns. */
const olderName = (t: AccountType) => (t.fullName && t.fullName.trim() !== (t.name ?? '').trim() ? t.fullName.trim() : '')
const fieldsOf = (t: AccountType): Fields => ({
  name: t.name ?? '', fullName: olderName(t), priority: text(t.priority), interestRate: text(t.interestRate), minBalance: text(t.minBalance), maxBalance: text(t.maxBalance),
  dormantAfterMonths: text(t.dormantAfterMonths), disabled: t.disabled,
})
const formOf = (f: Fields, reason: string): AccountTypeForm => ({
  name: f.name.trim(), fullName: f.fullName.trim(), priority: number(f.priority), interestRate: number(f.interestRate), minBalance: number(f.minBalance), maxBalance: number(f.maxBalance),
  dormantAfterMonths: number(f.dormantAfterMonths), disabled: f.disabled, reason,
})
const NUMERIC = ['priority', 'interestRate', 'minBalance', 'maxBalance', 'dormantAfterMonths'] as const
const same = (a: Fields, b: Fields) =>
  a.name.trim() === b.name.trim() && a.fullName.trim() === b.fullName.trim() && a.disabled === b.disabled && NUMERIC.every((k) => number(a[k]) === number(b[k]))
const NAME_TIP = 'Up to 20 characters. This is the name shown everywhere: lists, the teller window, passbooks, receipts and reports.'
const code4 = (n: number) => String(n).padStart(4, '0')
const label = (a: ChartAccount) => `${a.code} ${a.name ?? ''}`

/** What the class's own settings mean, shown under each field. */
const HELP: Record<number, { interestRate?: string; minBalance?: string }> = {
  22: { interestRate: 'Percent a year. Used by the savings interest run and given to accounts opened from now on.', minBalance: 'The balance a withdrawal must leave in the account.' },
  24: { minBalance: 'The smallest amount that can be placed.' },
  31: { minBalance: 'The share capital a member of this type must hold.' },
}

/**
 * Account types outside loans (the old S/L Type Table): the savings, time deposit, share capital, receivable and payable types and the chart accounts each posts to.
 * Administrators only. Loan types are kept in Loan Setup and shown read only; the cash class keeps its fixed types, but its links can be repointed and a drawer added.
 */
/**
 * The product kinds Settings > Products (and Accounting) open this screen on: the classes each covers, and its title and words. Without one, every
 * class is listed, as before.
 */
export const PRODUCT_KINDS: Record<string, { title: string; text: string; classes: number[] }> = {
  deposits: { title: 'Savings and deposit products', classes: [22, 24, 25], text: 'The savings, time deposit and investment deposit plan types members hold: their interest rates, minimum and maximum balances, dormancy period, and the chart accounts each posts to.' },
  shares: { title: 'Share capital products', classes: [31], text: 'The share capital types members subscribe to: the share capital a member of the type must hold, and the chart accounts each posts to.' },
  funds: { title: 'Fund and payable products', classes: [26, 29], text: 'Member funds (such as mutual benefit and hospitalization assistance) and payables kept per member, and the chart accounts each posts to.' },
  receivables: { title: 'Receivable products', classes: [13, 14], text: 'Receivables kept per member, such as cash advances, and the chart accounts each posts to. Loans are kept in Loan products.' },
  cash: { title: 'Cash and teller drawers', classes: [11], text: 'The cash accounts and each cashier’s and teller’s drawer. Their types are fixed; the chart accounts they post to can be repointed and a drawer added.' },
}

export default function AccountTypesPage({ kind }: { kind?: string | null } = {}) {
  const qc = useQueryClient()
  const all = useQuery({ queryKey: ['account-types'], queryFn: getAccountTypes })
  const product = kind ? PRODUCT_KINDS[kind] : undefined
  const classes = { ...all, data: product ? all.data?.filter((c) => product.classes.includes(c.id)) : all.data }
  const [picked, setPicked] = useState<{ cls: number; code: number | null } | null>(null)
  const [adding, setAdding] = useState<AccountTypeClass | null>(null)
  const [done, setDone] = useState<string | null>(null)

  const first = useMemo(() => {
    const c = classes.data?.find((x) => x.managed === 'EDIT' && x.types.length > 0)
    return c ? { cls: c.id, code: c.types[0].code as number | null } : null
  }, [classes.data])
  const at = picked ?? first
  const cls = classes.data?.find((c) => c.id === at?.cls) ?? null
  const type = cls?.types.find((t) => t.code === at?.code) ?? null
  const refresh = () => qc.invalidateQueries({ queryKey: ['account-types'] })
  const shown = (t: AccountType, msg: string) => { setAdding(null); setDone(msg); setPicked({ cls: t.classId, code: t.code }); void refresh() }

  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: { xs: 2, md: 3 }, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1180, mx: 'auto' }}>
        <PageTitle title={product ? product.title : 'Account Types'}>
          {product ? product.text : 'The kinds of savings, time deposit, share capital, receivable and payable accounts, and the chart accounts each one posts to.'} A type is never deleted, only switched off; every change needs a reason and is kept in the audit log.
        </PageTitle>
        {product && all.data && classes.data?.length === 0 && <Alert severity="info" sx={{ mb: 1.5 }}>This database has none of these accounts set up.</Alert>}
        {done && <Alert severity="success" onClose={() => setDone(null)} sx={{ mb: 1.5 }}>{done}</Alert>}
        {classes.error && <Alert severity="error" sx={{ mb: 1.5 }}>{(classes.error as Error).message}</Alert>}
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '300px 1fr' }, gap: 2, alignItems: 'start' }}>
          <Surface label="Classes and types">
            <Box component="nav" aria-label="Account types by class" sx={{ maxHeight: { md: 'calc(100dvh - 190px)' }, overflow: 'auto' }}>
              <List dense disablePadding>
                {(classes.data ?? []).map((c) => (
                  <Box component="li" key={c.id} sx={{ listStyle: 'none' }}>
                    <ListSubheader component="div" sx={{ display: 'flex', alignItems: 'center', lineHeight: '30px', fontSize: 12, fontWeight: 600, bgcolor: 'background.paper' }}>
                      <Box component="span" sx={{ flex: 1 }}>{c.id} {c.name}</Box>
                      <Box component="span" sx={{ fontWeight: 400, ...tabular }}>{c.types.length}</Box>
                    </ListSubheader>
                    <List dense disablePadding>
                      {c.types.map((t) => (
                        <ListItemButton key={t.code} selected={at?.cls === c.id && at.code === t.code} onClick={() => { setDone(null); setPicked({ cls: c.id, code: t.code }) }} sx={{ py: 0.25, pl: 3, opacity: t.disabled ? 0.55 : 1 }}>
                          <Typography variant="body2" component="span" color="text.secondary" sx={{ width: 40, ...tabular }}>{code4(t.code)}</Typography>
                          <Typography component="span" noWrap>{t.name}</Typography>
                        </ListItemButton>
                      ))}
                      {c.types.length === 0 && (
                        <ListItemButton selected={at?.cls === c.id} onClick={() => { setDone(null); setPicked({ cls: c.id, code: null }) }} sx={{ py: 0.25, pl: 3 }}>
                          <Typography component="span" color="text.secondary">No types yet</Typography>
                        </ListItemButton>
                      )}
                    </List>
                  </Box>
                ))}
              </List>
            </Box>
          </Surface>

          {cls && type ? (
            <Stack spacing={2}>
              <TypePanel key={`${cls.id}/${type.code}`} cls={cls} type={type} onAdd={() => { setDone(null); setAdding(cls) }} onSaved={(msg) => { setDone(msg); void refresh() }}
                onDeleted={(msg) => { setDone(msg); setPicked(null); void refresh() }} />
              {cls.managed === 'CASH' && <CashSetupPanel onSaved={(msg) => { setDone(msg); void qc.invalidateQueries({ queryKey: ['account-type-links'] }); void refresh() }} />}
              {cls.managed === 'CASH' && <Drawers onSaved={(msg) => { setDone(msg); void qc.invalidateQueries({ queryKey: ['account-type-links'] }); void refresh() }} />}
            </Stack>
          ) : cls && cls.types.length === 0 && cls.managed === 'CASH' ? (
            <CashSetupPanel onSaved={(msg) => { setDone(msg); void refresh() }} />
          ) : cls && cls.types.length === 0 ? (
            <Surface label="Class without types">
              <Box sx={{ p: 2 }}>
                <Typography variant="h5" component="h2">{cls.name}</Typography>
                <Typography color="text.secondary" sx={{ mt: 0.5, mb: 1.5 }}>This class has no account types yet. Its first type starts with a name and the chart account its balance posts to; the other ledger links are added afterwards.</Typography>
                <Button variant="contained" startIcon={<FiPlus />} onClick={() => { setDone(null); setAdding(cls) }}>Add the first type</Button>
              </Box>
            </Surface>
          ) : <Typography color="text.secondary" sx={{ p: 2 }}>{classes.isLoading ? 'Loading…' : 'Choose a type on the left.'}</Typography>}
        </Box>
        {adding && adding.types.length > 0 && (
          <NewTypeDialog cls={adding} onClose={() => setAdding(null)}
            onSaved={(t) => shown(t, `${code4(t.code)} ${t.name} was added. It posts to the same accounts as the type it was copied from until its links are changed.`)} />
        )}
        {adding && adding.types.length === 0 && (
          <FirstTypeDialog cls={adding} onClose={() => setAdding(null)} onSaved={(t) => shown(t, `${code4(t.code)} ${t.name} was added with its balance link. Add its other ledger links below.`)} />
        )}
      </Box>
    </Box>
  )
}

function TypeFields({ cls, f, set, disabled }: { cls: AccountTypeClass; f: Fields; set: (p: Partial<Fields>) => void; disabled: boolean }) {
  const help = HELP[cls.id] ?? {}
  const amount = (v: string) => v.replace(/[^0-9.]/g, '')
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' }, gap: 1.75 }}>
      <Hinted tip={NAME_TIP}><TextField required label="Name" value={f.name} disabled={disabled} onChange={(e) => set({ name: e.target.value })} slotProps={{ htmlInput: { maxLength: 20 } }} /></Hinted>
      {f.fullName && (
        <Box sx={{ gridColumn: '1 / -1', display: 'flex', alignItems: 'center', flexWrap: 'wrap', columnGap: 1 }}>
          <Typography variant="body2" color="text.secondary">Also has a longer name from the old system: {f.fullName}</Typography>
          {!disabled && <Button size="small" onClick={() => set({ fullName: '' })}>Use the name only</Button>}
        </Box>
      )}
      {cls.fields.includes('interestRate') && (
        <Hinted tip={help.interestRate}><TextField label="Interest rate (% a year)" value={f.interestRate} disabled={disabled} onChange={(e) => set({ interestRate: amount(e.target.value) })} slotProps={{ htmlInput: { inputMode: 'decimal' } }} /></Hinted>
      )}
      {cls.fields.includes('minBalance') && (
        <Hinted tip={help.minBalance}><TextField label="Minimum balance" value={f.minBalance} disabled={disabled} onChange={(e) => set({ minBalance: amount(e.target.value) })} slotProps={{ htmlInput: { inputMode: 'decimal' } }} /></Hinted>
      )}
      {cls.fields.includes('maxBalance') && (
        <Hinted tip="The most an account may hold; a deposit that would go over it is refused. Empty for no limit."><TextField label="Maximum balance" value={f.maxBalance} disabled={disabled} onChange={(e) => set({ maxBalance: amount(e.target.value) })} slotProps={{ htmlInput: { inputMode: 'decimal' } }}
          /></Hinted>
      )}
      {cls.fields.includes('dormantAfterMonths') && (
        <Hinted tip="With no deposit or withdrawal for this long, an account is listed under Savings dormancy for review. Empty for never."><TextField label="Dormant after (months)" value={f.dormantAfterMonths} disabled={disabled} onChange={(e) => set({ dormantAfterMonths: e.target.value.replace(/\D/g, '') })} slotProps={{ htmlInput: { inputMode: 'numeric', maxLength: 3 } }}
          /></Hinted>
      )}
    </Box>
  )
}

function TypePanel({ cls, type, onAdd, onSaved, onDeleted }: { cls: AccountTypeClass; type: AccountType; onAdd: () => void; onSaved: (msg: string) => void; onDeleted: (msg: string) => void }) {
  const qc = useQueryClient()
  const editable = cls.managed === 'EDIT'
  const linksEditable = editable || cls.managed === 'CASH'
  const linksKey = ['account-type-links', cls.id, type.code]
  const [f, setF] = useState<Fields>(fieldsOf(type))
  const [asking, setAsking] = useState(false)
  const [moving, setMoving] = useState<AccountTypeLink | null>(null)
  const [removing, setRemoving] = useState<AccountTypeLink | null>(null)
  const [linking, setLinking] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const dirty = editable && !same(f, fieldsOf(type))
  useLeaveGuard(dirty ? `The changes to ${type.name ?? 'this type'} have not been saved.` : null)

  const links = useQuery({ queryKey: linksKey, queryFn: () => getAccountTypeLinks(cls.id, type.code) })
  const save = useMutation({
    onError: notifyError,
    mutationFn: (reason: string) => updateAccountType(cls.id, type.code, formOf(f, reason)),
    onSuccess: (t) => { setAsking(false); setF(fieldsOf(t)); onSaved(`${code4(t.code)} ${t.name} was saved.`) },
  })
  const remove = useMutation({
    mutationFn: (reason: string) => removeAccountTypeLink(cls.id, type.code, removing!.event, removing!.number, reason),
    onSuccess: (rows) => { qc.setQueryData(linksKey, rows); onSaved(`${removing!.what} was removed from ${type.name}.`); setRemoving(null) },
  })
  const check = useQuery({ queryKey: ['account-type-delete', cls.id, type.code], queryFn: () => checkAccountTypeDelete(cls.id, type.code), enabled: deleting, staleTime: 0, gcTime: 0 })
  const del = useMutation({
    mutationFn: (reason: string) => deleteAccountType(cls.id, type.code, reason),
    onSuccess: (r) => { setDeleting(false); onDeleted(`${code4(type.code)} ${r.name ?? type.name} was deleted${r.links ? `, with its ${r.links} ledger link${r.links === 1 ? '' : 's'}` : ''}.`) },
  })
  const valid = f.name.trim() !== '' && NUMERIC.every((k) => f[k].trim() === '' || !isNaN(Number(f[k])))

  return (
    <Stack spacing={2}>
      <Surface label="Type">
        <Box sx={{ p: 2 }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap', mb: 1.5 }}>
            <Box sx={{ flex: 1, minWidth: 220 }}>
              <Typography variant="h5" component="h2" sx={{ lineHeight: 1.3 }}>{type.name}</Typography>
              <Typography variant="body2" color="text.secondary">
                {cls.name} · type {code4(type.code)}{type.accounts != null && ` · ${type.accounts.toLocaleString()} account${type.accounts === 1 ? '' : 's'}`}
              </Typography>
            </Box>
            <Dot color={type.disabled ? 'grey' : 'success'}>{type.disabled ? 'Switched off' : 'In use'}</Dot>
            {editable && <Button size="small" startIcon={<FiPlus />} onClick={onAdd}>New type</Button>}
            {editable && <Button size="small" color="error" startIcon={<FiTrash2 />} onClick={() => { del.reset(); setDeleting(true) }}>Delete type</Button>}
          </Box>
          {!editable && (
            <Alert severity="info" sx={{ mb: 1.5 }}>
              {cls.managed === 'LOAN_SETUP'
                ? 'Loan types are kept in Settings > Products > Loan products, where their terms, rates, ledger links and deductions are changed. They are shown here for reference.'
                : 'The cash types are fixed: the system posts cash, cheques and bank money through them. A ledger link can be pointed at another account, and a cashier’s or teller’s drawer can be added below.'}
            </Alert>
          )}
          <TypeFields cls={cls} f={f} set={(p) => setF((cur) => ({ ...cur, ...p }))} disabled={!editable} />
          {editable && (
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mt: 1.5, flexWrap: 'wrap' }}>
              <FormControlLabel control={<Switch checked={!f.disabled} onChange={(e) => setF((cur) => ({ ...cur, disabled: !e.target.checked }))} />} label="In use" />
              <Typography variant="body2" color="text.secondary" sx={{ flex: 1, minWidth: 200 }}>Switched off, it is no longer offered for new accounts; the accounts already opened carry on.</Typography>
              <Button onClick={() => setF(fieldsOf(type))} disabled={!dirty}>Undo</Button>
              <Button variant="contained" disabled={!dirty || !valid} onClick={() => { save.reset(); setAsking(true) }}>Save</Button>
            </Box>
          )}
        </Box>
      </Surface>

      <Box>
        <Box sx={{ display: 'flex', alignItems: 'flex-end' }}>
          <Box sx={{ flex: 1 }}><SectionLabel>Ledger accounts it posts to</SectionLabel></Box>
          {editable && <Button size="small" startIcon={<FiPlus />} onClick={() => setLinking(true)} sx={{ mb: 0.5 }}>Add a link</Button>}
        </Box>
        <Surface label="Ledger accounts">
          {links.error && <Alert severity="error" sx={{ m: 1.5 }}>{(links.error as Error).message}</Alert>}
          <Box sx={{ maxHeight: 360, overflow: 'auto' }}>
            <Table size="small" stickyHeader aria-label="Ledger accounts" sx={(t) => ({ ...listTable(t), '& .MuiTableCell-stickyHeader': { bgcolor: t.palette.background.paper } })}>
              <TableHead><TableRow><TableCell>Posting</TableCell><TableCell>{cls.managed === 'CASH' ? 'Drawer or bank' : 'Account status'}</TableCell><TableCell>Chart account</TableCell><TableCell sx={{ width: 170 }} /></TableRow></TableHead>
              <TableBody>
                {(links.data ?? []).map((l) => (
                  <TableRow key={`${l.event}/${l.number}`} hover>
                    <TableCell>{l.what}</TableCell>
                    <TableCell>{l.statusName ?? 'Any'}</TableCell>
                    <TableCell>
                      <Box component="span" sx={tabular}>{l.account}</Box> {l.accountName}
                      {!l.accountActive && <Typography variant="body2" component="span" color="error" sx={{ ml: 1 }}>switched off</Typography>}
                    </TableCell>
                    <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                      {linksEditable && <Button size="small" onClick={() => setMoving(l)}>Change</Button>}
                      {l.removable && <Button size="small" color="error" onClick={() => { remove.reset(); setRemoving(l) }}>Remove</Button>}
                    </TableCell>
                  </TableRow>
                ))}
                {links.data?.length === 0 && <TableRow><TableCell colSpan={4} align="center" sx={{ py: 3, color: 'text.secondary' }}>This type has no ledger link, so nothing can be posted to it yet.</TableCell></TableRow>}
              </TableBody>
            </Table>
          </Box>
        </Surface>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.75, px: 0.5 }}>
          A link can be pointed at another account of the same kind. Entries already posted keep the account they were posted to{editable ? '; a link that entries were posted through cannot be removed' : ''}.
        </Typography>
      </Box>

      <ReasonDialog open={asking} title={`Save ${type.name ?? 'this type'}`} intro={summary(cls, fieldsOf(type), f)} confirm="Save" busy={save.isPending}
        error={save.error ? (save.error as Error).message : null} onCancel={() => setAsking(false)} onConfirm={(r) => save.mutate(r)} />
      <ReasonDialog open={removing != null} danger title={`Remove ${removing?.what.toLowerCase() ?? 'this link'}`} confirm="Remove link" busy={remove.isPending}
        intro={`${type.name} will no longer be able to post ${removing?.what.toLowerCase() ?? 'this'}${removing?.statusName ? ` for ${removing.statusName.toLowerCase()} accounts` : ''}. This is only allowed while nothing was posted through the link.`}
        error={remove.error ? (remove.error as Error).message : null} onCancel={() => setRemoving(null)} onConfirm={(r) => remove.mutate(r)} />
      <ReasonDialog open={deleting && check.data?.deletable === true} danger title={`Delete ${type.name ?? 'this type'}`} confirm="Delete type" busy={del.isPending}
        intro={`Nothing was ever opened or posted under ${type.name ?? 'this type'}, so it can be deleted${check.data?.links ? ` with its ${check.data.links} ledger link${check.data.links === 1 ? '' : 's'}` : ''}. Its settings are kept in the audit log. This cannot be undone here.`}
        error={del.error ? (del.error as Error).message : null} onCancel={() => setDeleting(false)} onConfirm={(r) => del.mutate(r)} />
      {deleting && check.data?.deletable !== true && (
        <Dialog open onClose={() => setDeleting(false)} fullWidth maxWidth="xs" aria-labelledby="cannot-delete-title">
          <DialogTitle id="cannot-delete-title">{check.data ? `${type.name ?? 'This type'} cannot be deleted` : `Delete ${type.name ?? 'this type'}`}</DialogTitle>
          <DialogContent>
            {check.isLoading && <Loading what="what uses this type" />}
            {check.error && <Alert severity="error">{(check.error as Error).message}</Alert>}
            {check.data?.problems.map((p) => <Typography key={p}>{p}</Typography>)}
          </DialogContent>
          <DialogActions>
            <Button onClick={() => setDeleting(false)}>Close</Button>
            {check.data && !type.disabled && (
              <Button variant="contained" onClick={() => { setDeleting(false); setF((cur) => ({ ...cur, disabled: true })); save.reset(); setAsking(true) }}>Switch it off instead</Button>
            )}
          </DialogActions>
        </Dialog>
      )}
      {moving && (
        <LinkDialog cls={cls} type={type} link={moving} onClose={() => setMoving(null)}
          onSaved={(rows) => { qc.setQueryData(linksKey, rows); setMoving(null); onSaved(`${moving.what} of ${type.name} now posts to another account.`) }} />
      )}
      {linking && (
        <AddLinkDialog cls={cls} type={type} existing={links.data ?? []} onClose={() => setLinking(false)}
          onSaved={(rows, what) => { qc.setQueryData(linksKey, rows); setLinking(false); onSaved(`${what} was added to ${type.name}.`) }} />
      )}
    </Stack>
  )
}

/** What is about to change, in a sentence, so the reason is given knowing it. */
function summary(cls: AccountTypeClass, was: Fields, now: Fields): string {
  const parts: string[] = []
  if (was.name.trim() !== now.name.trim()) parts.push(`name to ${now.name.trim()}`)
  if (was.fullName && !now.fullName) parts.push('the old longer name dropped')
  if (cls.fields.includes('interestRate') && number(was.interestRate) !== number(now.interestRate)) parts.push(`interest rate from ${was.interestRate || 'none'} to ${now.interestRate || 'none'} percent`)
  const amount = (v: string, none: string) => (v ? money(Number(v)) : none)
  if (cls.fields.includes('minBalance') && number(was.minBalance) !== number(now.minBalance)) parts.push(`minimum balance from ${amount(was.minBalance, 'none')} to ${amount(now.minBalance, 'none')}`)
  if (cls.fields.includes('maxBalance') && number(was.maxBalance) !== number(now.maxBalance)) parts.push(`maximum balance from ${amount(was.maxBalance, 'no limit')} to ${amount(now.maxBalance, 'no limit')}`)
  if (cls.fields.includes('dormantAfterMonths') && number(was.dormantAfterMonths) !== number(now.dormantAfterMonths)) parts.push(`dormant after ${was.dormantAfterMonths || 'never'} to ${now.dormantAfterMonths ? `${now.dormantAfterMonths} months` : 'never'}`)
  if (was.disabled !== now.disabled) parts.push(now.disabled ? 'switched off' : 'switched on')
  return parts.length ? `Changing: ${parts.join('; ')}.` : 'Nothing has changed.'
}

function NewTypeDialog({ cls, onClose, onSaved }: { cls: AccountTypeClass; onClose: () => void; onSaved: (t: AccountType) => void }) {
  const sources = cls.types
  const [copyFrom, setCopyFrom] = useState(sources.find((t) => !t.disabled)?.code ?? sources[0].code)
  const src = sources.find((t) => t.code === copyFrom) ?? sources[0]
  const [f, setF] = useState<Fields>({ ...fieldsOf(src), name: '', fullName: '', disabled: false })
  const [reason, setReason] = useState('')
  const save = useMutation({
    onError: notifyError, mutationFn: () => createAccountType(cls.id, copyFrom, formOf(f, reason.trim())), onSuccess: onSaved })
  const ready = f.name.trim() !== '' && reason.trim() !== '' && !save.isPending
  return (
    <Dialog open onClose={save.isPending ? undefined : onClose} fullWidth maxWidth="sm">
      <form onSubmit={(e) => { e.preventDefault(); if (ready) save.mutate() }}>
        <DialogTitle>New {cls.name.toLowerCase()} type</DialogTitle>
        <DialogContent>
          <Stack spacing={1.75} sx={{ mt: 1 }}>
            <Hinted tip="The new type starts with this one's settings and posts to the same chart accounts until its links are changed."><TextField select label="Copy from" value={copyFrom}
              onChange={(e) => { const c = Number(e.target.value); setCopyFrom(c); const s = sources.find((t) => t.code === c); if (s) setF((cur) => ({ ...fieldsOf(s), name: cur.name, fullName: '', disabled: false })) }}>
              {sources.map((t) => <MenuItem key={t.code} value={t.code}>{code4(t.code)} {t.name}{t.disabled ? ' (switched off)' : ''}</MenuItem>)}
            </TextField></Hinted>
            <TypeFields cls={cls} f={f} set={(p) => setF((cur) => ({ ...cur, ...p }))} disabled={false} />
            <Hinted tip="Kept in the audit log. A type cannot be deleted afterwards, only switched off."><TextField required label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} slotProps={{ htmlInput: { maxLength: 255 } }} /></Hinted>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} disabled={save.isPending}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={!ready}>Add type</Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}

/**
 * The chart accounts the server suggests for a ledger link (what the class's other types use for the same posting, then accounts whose names match), as chips that fill in the
 * account; why each is suggested shows on hover. Only those among `options` are shown, so a suggestion is always one the field accepts.
 */
function Suggested({ cls, event, number, type, name, options, chosen, onPick }: {
  cls: number; event: number | null; number: number; type?: number; name?: string; options: ChartAccount[]; chosen: string | null; onPick: (a: ChartAccount) => void
}) {
  // the name typed so far, once typing pauses
  const [typed, setTyped] = useState(name ?? '')
  useEffect(() => { const t = setTimeout(() => setTyped(name ?? ''), 400); return () => clearTimeout(t) }, [name])
  const q = useQuery({
    queryKey: ['link-suggestions', cls, event, number, type ?? null, typed.trim()],
    queryFn: () => getLinkSuggestions(cls, event!, number, type, typed.trim() || undefined),
    enabled: event != null && (type != null || typed.trim() !== ''), staleTime: 60_000,
  })
  const shown = (q.data ?? []).flatMap((s) => { const a = options.find((o) => o.code === s.account); return a ? [{ s, a }] : [] }).slice(0, 3)
  if (shown.length === 0) return null
  return (
    <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 0.75 }}>
      <Typography variant="body2" color="text.secondary">Suggested:</Typography>
      {shown.map(({ s, a }) => (
        <Tooltip key={s.account} title={s.why} describeChild>
          <Chip size="small" label={label(a)} onClick={() => onPick(a)} color={chosen === a.code ? 'primary' : 'default'} variant={chosen === a.code ? 'filled' : 'outlined'} />
        </Tooltip>
      ))}
    </Box>
  )
}

/** Posting accounts that are in use. */
function usePostingAccounts() {
  const chart = useQuery({ queryKey: ['chart'], queryFn: getChart })
  const options = useMemo(() => (chart.data ?? []).filter((a) => !a.summary && !a.disabled), [chart.data])
  return { chart, options }
}

function FirstTypeDialog({ cls, onClose, onSaved }: { cls: AccountTypeClass; onClose: () => void; onSaved: (t: AccountType) => void }) {
  const { chart, options } = usePostingAccounts()
  const [name, setName] = useState('')
  const [account, setAccount] = useState<ChartAccount | null>(null)
  const [reason, setReason] = useState('')
  const save = useMutation({
    onError: notifyError, mutationFn: () => createFirstAccountType(cls.id, { name: name.trim(), fullName: '', account: account!.code, reason: reason.trim() }), onSuccess: onSaved })
  const ready = name.trim() !== '' && account != null && reason.trim() !== '' && !save.isPending
  return (
    <Dialog open onClose={save.isPending ? undefined : onClose} fullWidth maxWidth="sm">
      <form onSubmit={(e) => { e.preventDefault(); if (ready) save.mutate() }}>
        <DialogTitle>First {cls.name.toLowerCase()} type</DialogTitle>
        <DialogContent>
          <Stack spacing={1.75} sx={{ mt: 1 }}>
            <Hinted tip={NAME_TIP}><TextField required autoFocus label="Name" value={name} onChange={(e) => setName(e.target.value)} slotProps={{ htmlInput: { maxLength: 20 } }} /></Hinted>
            <Hinted tip="The chart account that carries the balance of accounts of this type"><Autocomplete options={options} value={account} onChange={(_, v) => setAccount(v)} loading={chart.isLoading} getOptionLabel={label} isOptionEqualToValue={(a, b) => a.code === b.code}
              renderInput={(p) => <TextField {...p} required label="Balance posts to" />} /></Hinted>
            <Suggested cls={cls.id} event={11} number={99} name={name.trim()} options={options} chosen={account?.code ?? null} onPick={setAccount} />
            <Hinted tip="Kept in the audit log. A type cannot be deleted afterwards, only switched off."><TextField required label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} slotProps={{ htmlInput: { maxLength: 255 } }} /></Hinted>
            {(chart.error) && <Alert severity="error">{((chart.error) as Error).message}</Alert>}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} disabled={save.isPending}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={!ready}>Add type</Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}

function LinkDialog({ cls, type, link, onClose, onSaved }: { cls: AccountTypeClass; type: AccountType; link: AccountTypeLink; onClose: () => void; onSaved: (rows: AccountTypeLink[]) => void }) {
  const all = useQuery({ queryKey: ['chart'], queryFn: getChart })
  const [account, setAccount] = useState<ChartAccount | null>(null)
  const [reason, setReason] = useState('')
  // posting accounts in use, of the same kind as the one linked now
  const kind = all.data?.find((a) => a.code === link.account)?.type
  const options = useMemo(() => (all.data ?? []).filter((a) => !a.summary && !a.disabled && a.code !== link.account && (kind == null || a.type === kind)), [all.data, link.account, kind])
  const save = useMutation({
    onError: notifyError, mutationFn: () => changeAccountTypeLink(cls.id, type.code, link.event, link.number, account!.code, reason.trim()), onSuccess: onSaved })
  const ready = account != null && reason.trim() !== '' && !save.isPending
  return (
    <Dialog open onClose={save.isPending ? undefined : onClose} fullWidth maxWidth="sm">
      <form onSubmit={(e) => { e.preventDefault(); if (ready) save.mutate() }}>
        <DialogTitle>{link.what}{link.statusName ? ` (${link.statusName.toLowerCase()})` : ''} of {type.name}</DialogTitle>
        <DialogContent>
          <Stack spacing={1.75} sx={{ mt: 1 }}>
            <Typography color="text.secondary">
              Now posts to <Box component="span" sx={tabular}>{link.account}</Box> {link.accountName}. New entries will go to the account you choose; entries already posted stay where they are, so move the balance with a journal if the old account should be emptied.
            </Typography>
            <Hinted tip="Accounts of the same kind that take postings and are in use"><Autocomplete options={options} value={account} onChange={(_, v) => setAccount(v)} loading={all.isLoading} getOptionLabel={label} isOptionEqualToValue={(a, b) => a.code === b.code}
              renderInput={(p) => <TextField {...p} required autoFocus label="Post to" />} /></Hinted>
            <Suggested cls={cls.id} event={link.event} number={link.number} type={type.code} options={options} chosen={account?.code ?? null} onPick={setAccount} />
            <Hinted tip="Kept in the audit log"><TextField required label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} slotProps={{ htmlInput: { maxLength: 255 } }} /></Hinted>
            {(all.error) && <Alert severity="error">{((all.error) as Error).message}</Alert>}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} disabled={save.isPending}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={!ready}>Change account</Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}

function AddLinkDialog({ cls, type, existing, onClose, onSaved }: {
  cls: AccountTypeClass; type: AccountType; existing: AccountTypeLink[]; onClose: () => void; onSaved: (rows: AccountTypeLink[], what: string) => void
}) {
  const { chart, options } = usePostingAccounts()
  const choices = useQuery({ queryKey: ['account-type-link-options', cls.id], queryFn: () => getAccountTypeLinkOptions(cls.id), staleTime: 300_000 })
  const [event, setEvent] = useState('')
  const [status, setStatus] = useState('')
  const [account, setAccount] = useState<ChartAccount | null>(null)
  const [reason, setReason] = useState('')
  const has = (e: number, n: number) => existing.some((l) => l.event === e && l.number === n)
  // a balance link is per account status; every other posting has one link
  const statuses = (choices.data?.statuses ?? []).filter((s) => !has(11, s.id))
  const events = (choices.data?.events ?? []).filter((e) => (e.id === 11 ? statuses.length > 0 : !has(e.id, 99)))
  const byStatus = event === '11'
  const what = choices.data?.events.find((e) => String(e.id) === event)?.name ?? 'The link'
  const save = useMutation({
    onError: notifyError,
    mutationFn: () => addAccountTypeLink(cls.id, type.code, Number(event), byStatus ? Number(status) : 99, account!.code, reason.trim()),
    onSuccess: (rows) => onSaved(rows, what),
  })
  const ready = event !== '' && (!byStatus || status !== '') && account != null && reason.trim() !== '' && !save.isPending
  return (
    <Dialog open onClose={save.isPending ? undefined : onClose} fullWidth maxWidth="sm">
      <form onSubmit={(e) => { e.preventDefault(); if (ready) save.mutate() }}>
        <DialogTitle>Add a ledger link to {type.name}</DialogTitle>
        <DialogContent>
          <Stack spacing={1.75} sx={{ mt: 1 }}>
            {choices.data && events.length === 0 && <Alert severity="info">This type already has every link its class uses.</Alert>}
            <Hinted tip="What the system posts through this link"><TextField select required label="Posting" value={event} onChange={(e) => { setEvent(e.target.value); setStatus('') }}>
              {events.map((e) => <MenuItem key={e.id} value={String(e.id)}>{e.name}</MenuItem>)}
            </TextField></Hinted>
            {byStatus && (
              <TextField select required label="For accounts that are" value={status} onChange={(e) => setStatus(e.target.value)}>
                {statuses.map((s) => <MenuItem key={s.id} value={String(s.id)}>{s.name}</MenuItem>)}
              </TextField>
            )}
            <Hinted tip="An account that takes postings and is in use"><Autocomplete options={options} value={account} onChange={(_, v) => setAccount(v)} loading={chart.isLoading} getOptionLabel={label} isOptionEqualToValue={(a, b) => a.code === b.code}
              renderInput={(p) => <TextField {...p} required label="Post to" />} /></Hinted>
            <Suggested cls={cls.id} event={event === '' || (byStatus && status === '') ? null : Number(event)} number={byStatus ? Number(status || 0) : 99} type={type.code}
              options={options} chosen={account?.code ?? null} onPick={setAccount} />
            <Hinted tip="Kept in the audit log"><TextField required label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} slotProps={{ htmlInput: { maxLength: 255 } }} /></Hinted>
            {(chart.error || choices.error) && <Alert severity="error">{((chart.error ?? choices.error) as Error).message}</Alert>}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} disabled={save.isPending}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={!ready}>Add link</Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}

/** The cashiers' and tellers' drawers the ledger knows, who holds each number, and adding one. */
function Drawers({ onSaved }: { onSaved: (msg: string) => void }) {
  const qc = useQueryClient()
  const drawers = useQuery({ queryKey: ['cash-drawers'], queryFn: getCashDrawers })
  const [adding, setAdding] = useState(false)
  const [kind, setKind] = useState('53')
  const [numberText, setNumberText] = useState('')
  const taken = (drawers.data ?? []).filter((d) => String(d.event) === kind).map((d) => d.number)
  const n = Number(numberText)
  const free = numberText !== '' && n >= 31 && n <= 94 && !taken.includes(n)
  const search = useChartSearch()
  const setup = useQuery({ queryKey: ['cash-setup'], queryFn: getCashSetup })
  const first = (drawers.data ?? []).every((d) => String(d.event) !== kind)
  const [accts, setAccts] = useState<Record<number, { code: string; description: string }>>({})
  const needed = FIRST_DRAWER_TYPES.filter((t) => setup.data?.types.includes(t.type))
  const accountsOk = !first || needed.filter((t) => t.required).every((t) => accts[t.type]?.code)
  const save = useMutation({
    onError: notifyError,
    mutationFn: (reason: string) => addCashDrawer(Number(kind), n, reason, first ? Object.fromEntries(Object.entries(accts).filter(([, a]) => a.code).map(([t, a]) => [t, a.code])) : undefined),
    onSuccess: (rows) => { qc.setQueryData(['cash-drawers'], rows); setAdding(false); setNumberText(''); setAccts({}); onSaved(`${kind === '52' ? 'Cashier' : 'Teller'} drawer ${n} was added. Give the number to a person in Settings > Users.`) },
  })
  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'flex-end' }}>
        <Box sx={{ flex: 1 }}><SectionLabel>Cash drawers</SectionLabel></Box>
        <Button size="small" startIcon={<FiPlus />} disabled={setup.data != null && !setup.data.ready} onClick={() => { save.reset(); setAdding(true) }} sx={{ mb: 0.5 }}>Add a drawer</Button>
      </Box>
      <Surface label="Cash drawers">
        {drawers.error && <Alert severity="error" sx={{ m: 1.5 }}>{(drawers.error as Error).message}</Alert>}
        <Box sx={{ maxHeight: 300, overflow: 'auto' }}>
          <Table size="small" stickyHeader aria-label="Cash drawers" sx={(t) => ({ ...listTable(t), '& .MuiTableCell-stickyHeader': { bgcolor: t.palette.background.paper } })}>
            <TableHead><TableRow><TableCell>Drawer</TableCell><TableCell>Cash account</TableCell><TableCell>Held by</TableCell></TableRow></TableHead>
            <TableBody>
              {(drawers.data ?? []).map((d) => (
                <TableRow key={`${d.event}/${d.number}`} hover>
                  <TableCell>{d.kind} {d.number}</TableCell>
                  <TableCell><Box component="span" sx={tabular}>{d.account}</Box> {d.accountName}</TableCell>
                  <TableCell sx={{ color: d.heldBy.length ? undefined : 'text.secondary' }}>{d.heldBy.length ? d.heldBy.join(', ') : 'No one'}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Box>
      </Surface>
      <AboutNote sx={{ mt: 0.75, px: 0.5 }}>
        A drawer is a number the ledger keeps cash, cheques and ATM items under. A person takes a drawer by having its number as their teller number in Settings &gt; Users.
      </AboutNote>
      <ReasonDialog open={adding} title="Add a cash drawer" confirm="Add drawer" busy={save.isPending} canSubmit={free && accountsOk}
        intro={first
          ? `This is the first ${kind === '52' ? 'cashier' : 'teller'}'s drawer, so choose the chart account each part of it posts to. Later drawers of this kind take the same accounts.`
          : 'The new drawer posts to the same accounts as the other drawers of its kind, for cash, cheques and ATM items.'}
        error={save.error ? (save.error as Error).message : null} onCancel={() => setAdding(false)} onConfirm={(r) => save.mutate(r)}>
        <TextField select label="Kind" value={kind} onChange={(e) => setKind(e.target.value)}>
          <MenuItem value="53">Teller</MenuItem><MenuItem value="52">Cashier</MenuItem>
        </TextField>
        <TextField required autoFocus label="Drawer number" value={numberText} onChange={(e) => setNumberText(e.target.value.replace(/\D/g, ''))} slotProps={{ htmlInput: { inputMode: 'numeric', maxLength: 2 } }}
          error={numberText !== '' && !free} helperText={numberText !== '' && taken.includes(n) ? 'That drawer already exists.' : '31 to 94, not used yet'} />
        {first && needed.map((t) => (
          <Box key={t.type}>
            <Typography variant="body2" sx={{ mb: 0.25 }} color="text.secondary">{t.label}{t.required ? ' *' : ' (optional)'}</Typography>
            <AccountPicker code={accts[t.type]?.code ?? ''} desc={accts[t.type]?.description ?? ''} label={`${t.label} account`} width={360} placeholder="Account number or name"
              search={search} recentKey="coop.cashSetupAccounts"
              onPick={(a) => setAccts((x) => ({ ...x, [t.type]: a ?? { code: '', description: '' } }))} />
          </Box>
        ))}
      </ReasonDialog>
    </Box>
  )
}

/** What a first drawer of a kind needs: the account for its cash and its cheques, and optionally its ATM items. */
const FIRST_DRAWER_TYPES = [
  { type: 1, label: "Drawer's cash", required: true },
  { type: 3, label: "Drawer's cheques and other cash items", required: true },
  { type: 97, label: "Drawer's ATM items", required: false },
]

/**
 * The cash class for the teller window: the system's cash types (numbers fixed, the ledger writes them by number) and the chart accounts of the vault and the cheques
 * on hand, which each cooperative chooses. Shown while anything is missing; it only adds.
 */
function CashSetupPanel({ onSaved }: { onSaved: (msg: string) => void }) {
  const qc = useQueryClient()
  const search = useChartSearch()
  const q = useQuery({ queryKey: ['cash-setup'], queryFn: getCashSetup })
  const [accts, setAccts] = useState<Record<string, { code: string; description: string }>>({})
  const [asking, setAsking] = useState(false)
  const save = useMutation({
    onError: notifyError,
    mutationFn: (reason: string) => setUpCash(Object.fromEntries(Object.entries(accts).filter(([, a]) => a.code).map(([k, a]) => [k, a.code])), reason),
    onSuccess: (r) => {
      qc.setQueryData(['cash-setup'], r)
      setAsking(false)
      setAccts({})
      onSaved(r.ready ? 'The cash types are set up. Add a drawer for each teller and cashier next.' : 'Saved. Some of the cash setup is still missing.')
    },
  })
  const s = q.data
  if (q.isLoading) return <Loading what="the cash setup" />
  if (q.error) return <Alert severity="error">{(q.error as Error).message}</Alert>
  if (!s || s.ready) return null
  const open = s.fixed.filter((f) => f.account == null)
  const ok = open.filter((f) => f.required).every((f) => accts[`${f.type}/${f.event}`]?.code)
  return (
    <Surface label="Set up the cash types">
      <Box sx={{ p: 2 }}>
        <Typography variant="h5" component="h2">Set up the cash types</Typography>
        <Alert severity="warning" sx={{ my: 1.5 }}>
          The teller window cannot take or count money on this database yet. Missing: {s.missing.join('; ')}.
        </Alert>
        <Typography color="text.secondary" sx={{ mb: 1.5 }}>
          The cash types (cash on hand, cash in bank, cheques and other cash items, ATM items) are the system&apos;s own and are added as they are. Choose the chart accounts
          your cooperative keeps that money in.
        </Typography>
        <Stack spacing={1.25}>
          {open.map((f) => {
            const k = `${f.type}/${f.event}`
            return (
              <Box key={k}>
                <Typography variant="body2" sx={{ mb: 0.25 }} color="text.secondary">{f.label}{f.required ? ' *' : ' (optional)'}</Typography>
                <AccountPicker code={accts[k]?.code ?? ''} desc={accts[k]?.description ?? ''} label={`${f.label} account`} width={360} placeholder="Account number or name"
                  search={search} recentKey="coop.cashSetupAccounts"
                  onPick={(a) => setAccts((x) => ({ ...x, [k]: a ?? { code: '', description: '' } }))} />
              </Box>
            )
          })}
        </Stack>
        <Button variant="contained" sx={{ mt: 2 }} disabled={!ok} onClick={() => { save.reset(); setAsking(true) }}>Set up the cash types</Button>
      </Box>
      <ReasonDialog open={asking} title="Set up the cash types" confirm="Set up" busy={save.isPending}
        intro="Adds the missing cash types and links the accounts chosen. Nothing already set up is changed."
        error={save.error ? (save.error as Error).message : null} onCancel={() => setAsking(false)} onConfirm={(r) => save.mutate(r)} />
    </Surface>
  )
}

/** Searches the chart this page already loads for accounts that take postings, by number or name. */
function useChartSearch() {
  const chart = useQuery({ queryKey: ['chart'], queryFn: getChart })
  return async (q: string) => {
    const t = q.trim().toLowerCase()
    return (chart.data ?? [])
      .filter((a) => !a.summary && !a.disabled && (a.code.startsWith(t) || (a.name ?? '').toLowerCase().includes(t)))
      .slice(0, 20).map((a) => ({ code: a.code, description: a.name ?? '' }))
  }
}
