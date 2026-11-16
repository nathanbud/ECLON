import { Box, Button, GlobalStyles, Typography } from '@mui/material'
import { useState } from 'react'
import { FiPrinter } from 'react-icons/fi'
import { brand } from './brand'

const inTauri = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window

/**
 * Prints the page. In the desktop app it goes straight to the computer's default printer with no print window; in a browser, or if the desktop app cannot reach the printer,
 * the usual print window opens (a browser never allows printing without it).
 */
async function printNow(): Promise<string | null> {
  if (inTauri) {
    try {
      const { invoke } = await import('@tauri-apps/api/core')
      await invoke('print_silent')
      return null
    } catch (e) {
      window.print()
      return `Printing straight to the printer did not work (${typeof e === 'string' ? e : (e as Error).message}); the print window was opened instead.`
    }
  }
  window.print()
  return null
}

export interface InvoiceLine { label: string; value: string; bold?: boolean }

/**
 * A button that prints the service invoice for a transaction just posted. The invoice is on the page but hidden; printing shows it alone (everything else is hidden while printing)
 * on a half-page slip: the cooperative, the receipt and control numbers, the date, the member, the particulars and amounts, the cashier, and lines to sign.
 */
export default function PrintInvoice({ title = 'SERVICE INVOICE', documentNo, controlNo, date, member, lines, cashier }: {
  title?: string; documentNo: string; controlNo: number; date: string; member: string; lines: InvoiceLine[]; cashier: string
}) {
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState<string | null>(null)
  const print = async () => { setBusy(true); setNote(await printNow()); setBusy(false) }
  const row = (l: InvoiceLine) => (
    <Box key={l.label} sx={{ display: 'flex', justifyContent: 'space-between', gap: 2, py: 0.3, fontWeight: l.bold ? 700 : 400 }}><span>{l.label}</span><span style={{ textAlign: 'right' }}>{l.value}</span></Box>
  )
  return (
    <>
      <GlobalStyles styles={{
        '#service-invoice': { display: 'none' },
        '@media print': {
          'body *': { visibility: 'hidden' },
          '#service-invoice, #service-invoice *': { visibility: 'visible' },
          '#service-invoice': { display: 'block', position: 'absolute', left: 0, top: 0, width: '148mm', padding: '10mm', color: '#000', background: '#fff', fontSize: '12pt' },
        },
      }} />
      {note && <Typography role="alert" sx={{ fontSize: 12, mr: 1, flex: 1 }} color="error">{note}</Typography>}
      <Button startIcon={<FiPrinter />} disabled={busy} onClick={() => { void print() }}>{busy ? 'Printing…' : 'Print invoice'}</Button>
      <Box id="service-invoice" aria-hidden>
        <Typography sx={{ fontWeight: 700, fontSize: '14pt', textAlign: 'center' }}>{brand.coop}</Typography>
        <Typography sx={{ fontWeight: 700, textAlign: 'center', letterSpacing: '0.08em', mb: 1.5 }}>{title}</Typography>
        <Box sx={{ borderTop: '1px solid #000', my: 1 }} />
        {row({ label: 'Receipt no.', value: documentNo })}
        {row({ label: 'Control no.', value: String(controlNo) })}
        {row({ label: 'Date', value: date })}
        {row({ label: 'Member', value: member })}
        <Box sx={{ borderTop: '1px solid #000', my: 1 }} />
        {lines.map(row)}
        <Box sx={{ borderTop: '1px solid #000', my: 1 }} />
        {row({ label: 'Cashier', value: cashier })}
        <Box sx={{ display: 'flex', justifyContent: 'space-between', mt: 6, gap: 4 }}>
          <Box sx={{ flex: 1, borderTop: '1px solid #000', textAlign: 'center', pt: 0.5, fontSize: '10pt' }}>Received by</Box>
          <Box sx={{ flex: 1, borderTop: '1px solid #000', textAlign: 'center', pt: 0.5, fontSize: '10pt' }}>Member&apos;s signature</Box>
        </Box>
        <Typography sx={{ fontSize: '9pt', textAlign: 'center', mt: 2 }}>This is a system-generated service invoice. Keep it for your records.</Typography>
      </Box>
    </>
  )
}
