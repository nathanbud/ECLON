import { Alert, Box, Button, Chip, FormControlLabel, MenuItem, Skeleton, Switch, TextField, Typography } from '@mui/material'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { FiUpload } from 'react-icons/fi'
import { getKyc, getKycScan, saveKyc, uploadKycScan, type Kyc, type KycInput, type KycStatus } from '../api'
import { type Id } from '../clients'
import { useMe } from '../session'
import { Section } from './fields'

const STATUS: Record<KycStatus, { label: string; color: 'default' | 'success' | 'warning' | 'error'; hint: string }> = {
  NONE: { label: 'Not started', color: 'default', hint: 'Nothing has been recorded for this member yet.' },
  INCOMPLETE: { label: 'Incomplete', color: 'warning', hint: 'The ID, its expiry date, the source of funds, the risk rating and the next review date are all needed.' },
  EXPIRED: { label: 'ID expired', color: 'error', hint: 'The ID on file has expired. Ask the member for a current one.' },
  REVIEW_DUE: { label: 'Review due', color: 'warning', hint: 'The review date has passed. Confirm the details are still right and set the next review date.' },
  COMPLETE: { label: 'Complete', color: 'success', hint: 'Everything needed is on file and up to date.' },
}

const RISKS: [string, string][] = [['LOW', 'Low'], ['MEDIUM', 'Medium'], ['HIGH', 'High']]
const REVIEW_YEARS: Record<string, number> = { LOW: 3, MEDIUM: 2, HIGH: 1 }

const empty: KycInput = { idType: null, idNumber: null, idIssuer: null, idExpiry: null, sourceOfFunds: null, pep: false, pepNote: null, risk: null, nextReview: null, remarks: null }
const toInput = (k: Kyc): KycInput => ({ idType: k.idType, idNumber: k.idNumber, idIssuer: k.idIssuer, idExpiry: k.idExpiry, sourceOfFunds: k.sourceOfFunds, pep: k.pep, pepNote: k.pepNote, risk: k.risk, nextReview: k.nextReview, remarks: k.remarks })

/** The review date a risk rating suggests, counted from today, as the server fills it when the field is left blank. */
function suggested(risk: string | null): string | null {
  if (!risk) return null
  const d = new Date()
  d.setFullYear(d.getFullYear() + (REVIEW_YEARS[risk] ?? 3))
  return d.toISOString().slice(0, 10)
}

/**
 * Know Your Customer: the ID the member presented (with a scan), where their money comes from, whether they hold a public position, how risky they are rated and when the record is next
 * looked at. Saved straight away by someone allowed to edit members, and every change is in the audit log with the ID number hidden. The status shows at the top and in the side panel.
 */
export function KycTab({ id, canImages }: { id: Id; canImages: boolean }) {
  const qc = useQueryClient()
  const canEdit = useMe().canEdit
  const { data, error, isLoading } = useQuery({ queryKey: ['kyc', id.branchCode, id.clientId], queryFn: () => getKyc(id.branchCode, id.clientId), staleTime: 60_000 })
  const scan = useQuery({ queryKey: ['kyc-scan', id.branchCode, id.clientId, data?.updatedAt], queryFn: () => getKycScan(id.branchCode, id.clientId), enabled: !!data?.hasScan, staleTime: 5 * 60_000, retry: false })
  useEffect(() => () => { if (scan.data) URL.revokeObjectURL(scan.data) }, [scan.data])
  const [form, setForm] = useState<KycInput>(empty)
  const [loaded, setLoaded] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)
  const file = useRef<HTMLInputElement>(null)

  // Fill the form from the record when it arrives (and again after a save), without overwriting what is being typed.
  const key = data ? `${data.updatedAt}|${data.status}` : null
  if (data && loaded !== key) { setLoaded(key); setForm(toInput(data)) }

  if (isLoading) return <Skeleton variant="rounded" height={260} />
  if (error || !data) return <Alert severity="error">{(error as Error | null)?.message ?? 'The KYC record could not be loaded.'}</Alert>

  const st = STATUS[data.status]
  const set = <K extends keyof KycInput>(k: K, v: KycInput[K]) => { setForm((f) => ({ ...f, [k]: v })); setMessage(null) }
  const text = (k: 'idNumber' | 'idIssuer' | 'sourceOfFunds' | 'pepNote' | 'remarks') => form[k] ?? ''
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['kyc', id.branchCode, id.clientId] }); void qc.invalidateQueries({ queryKey: ['kyc-scan', id.branchCode, id.clientId] }) }

  const save = async () => {
    setBusy(true)
    setMessage(null)
    try {
      const saved = await saveKyc(id.branchCode, id.clientId, form)
      qc.setQueryData(['kyc', id.branchCode, id.clientId], saved)
      setMessage({ ok: true, text: 'Saved. The change is recorded in the audit log.' })
    } catch (e) {
      setMessage({ ok: false, text: (e as Error).message })
    } finally {
      setBusy(false)
    }
  }

  const pickScan = async (f: File | undefined) => {
    if (!f) return
    setBusy(true)
    setMessage(null)
    try {
      await uploadKycScan(id.branchCode, id.clientId, f)
      refresh()
      setMessage({ ok: true, text: 'ID scan saved.' })
    } catch (e) {
      setMessage({ ok: false, text: (e as Error).message })
    } finally {
      setBusy(false)
    }
  }

  const ro = !canEdit
  const dirty = JSON.stringify(form) !== JSON.stringify(toInput(data))
  const hint = suggested(form.risk)
  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.25, mb: 1.25, flexWrap: 'wrap' }}>
        <Chip label={st.label} color={st.color} variant={data.status === 'COMPLETE' ? 'filled' : 'outlined'} sx={{ fontWeight: 600 }} />
        <Typography sx={{ fontSize: 13, flex: 1, minWidth: 220 }} color="text.secondary" role="status">{st.hint}</Typography>
        {data.updatedBy && <Typography sx={{ fontSize: 12 }} color="text.secondary">Last saved by {data.updatedBy}{data.updatedAt ? `, ${data.updatedAt.replace('T', ' ').slice(0, 16)}` : ''}</Typography>}
      </Box>

      <Section title="Valid ID">
        <TextField select size="small" label="Kind of ID" value={form.idType ?? ''} onChange={(e) => set('idType', e.target.value || null)} disabled={ro}>
          <MenuItem value=""><em>None</em></MenuItem>
          {data.idTypes.map((t) => <MenuItem key={t} value={t}>{t}</MenuItem>)}
        </TextField>
        <TextField size="small" label="ID number" value={text('idNumber')} onChange={(e) => set('idNumber', e.target.value)} disabled={ro} slotProps={{ htmlInput: { maxLength: 60 } }} />
        <TextField size="small" label="Issued by" value={text('idIssuer')} onChange={(e) => set('idIssuer', e.target.value)} disabled={ro} slotProps={{ htmlInput: { maxLength: 80 } }} />
        <TextField size="small" type="date" label="Expiry date" value={form.idExpiry ?? ''} onChange={(e) => set('idExpiry', e.target.value || null)} disabled={ro} slotProps={{ inputLabel: { shrink: true } }} />
        <Box sx={{ gridColumn: '1 / -1', display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap' }}>
          {scan.data
            ? <Box component="img" src={scan.data} alt="Scan of the ID" sx={{ maxHeight: 120, maxWidth: 220, objectFit: 'contain', borderRadius: '5px', border: 1, borderColor: 'divider', bgcolor: '#fff' }} />
            : <Typography sx={{ fontSize: 13 }} color="text.secondary">{data.hasScan ? 'Loading the scan…' : 'No scan of the ID on file.'}</Typography>}
          {canEdit && canImages && (
            <>
              <Button size="small" variant="outlined" startIcon={<FiUpload />} disabled={busy} onClick={() => file.current?.click()}>{data.hasScan ? 'Replace scan' : 'Add a scan'}</Button>
              <input ref={file} type="file" accept="image/jpeg,image/png" hidden onChange={(e) => { void pickScan(e.target.files?.[0]); e.target.value = '' }} />
            </>
          )}
        </Box>
      </Section>

      <Section title="Source of funds">
        <TextField size="small" label="Where the member's money comes from" value={text('sourceOfFunds')} onChange={(e) => set('sourceOfFunds', e.target.value)} disabled={ro}
          slotProps={{ htmlInput: { maxLength: 255 } }} sx={{ gridColumn: '1 / -1' }} placeholder="Salary, business income, remittance, pension…" />
        <FormControlLabel sx={{ m: 0 }} control={<Switch checked={form.pep} onChange={(e) => set('pep', e.target.checked)} disabled={ro} />} label="Politically exposed (holds or held a public position)" />
        {form.pep && <TextField size="small" label="Position held" value={text('pepNote')} onChange={(e) => set('pepNote', e.target.value)} disabled={ro} slotProps={{ htmlInput: { maxLength: 255 } }} />}
      </Section>

      <Section title="Risk and review">
        <TextField select size="small" label="Risk rating" value={form.risk ?? ''} onChange={(e) => set('risk', (e.target.value || null) as KycInput['risk'])} disabled={ro}>
          <MenuItem value=""><em>Not rated</em></MenuItem>
          {RISKS.map(([v, l]) => <MenuItem key={v} value={v}>{l}</MenuItem>)}
        </TextField>
        <TextField size="small" type="date" label="Next review" value={form.nextReview ?? ''} onChange={(e) => set('nextReview', e.target.value || null)} disabled={ro}
          slotProps={{ inputLabel: { shrink: true } }} helperText={!form.nextReview && hint ? `Left blank, it is set to ${hint}` : undefined} />
        <TextField size="small" label="Remarks" value={text('remarks')} onChange={(e) => set('remarks', e.target.value)} disabled={ro} slotProps={{ htmlInput: { maxLength: 255 } }} sx={{ gridColumn: '1 / -1' }} />
      </Section>

      {message && <Alert severity={message.ok ? 'success' : 'error'} sx={{ mb: 1 }} onClose={() => setMessage(null)}>{message.text}</Alert>}
      {canEdit
        ? (
          <Box sx={{ display: 'flex', gap: 1, alignItems: 'center' }}>
            <Button variant="contained" onClick={() => void save()} disabled={busy || !dirty}>{busy ? 'Saving…' : 'Save KYC'}</Button>
            {dirty && <Button onClick={() => { setForm(toInput(data)); setMessage(null) }} disabled={busy}>Undo changes</Button>}
          </Box>
        )
        : <Typography sx={{ fontSize: 13 }} color="text.secondary">You can read this record. Recording KYC needs permission to edit members.</Typography>}
    </Box>
  )
}
