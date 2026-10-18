import { Box, Button, Dialog, DialogActions, DialogContent, GlobalStyles, Typography } from '@mui/material'
import type { FoDetail } from './api'
import { money } from './FoTransactionForm'

/** A receipt for a posted transaction that prints on its own: while printing, everything except the receipt is hidden. */
export default function ReceiptDialog({ detail, user, open, onClose }: { detail: FoDetail | undefined; user: string; open: boolean; onClose: () => void }) {
  const h = detail?.header
  const row = (k: string, v: string, key?: string) => (
    <Box key={key ?? k} sx={{ display: 'flex', justifyContent: 'space-between', gap: 2, py: 0.25 }}><span>{k}</span><b style={{ textAlign: 'right' }}>{v}</b></Box>
  )
  const rule = <Box sx={{ borderTop: '1px dashed', my: 1 }} />
  return (
    <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth>
      {open && (
        <GlobalStyles styles={{
          '@media print': {
            'body *': { visibility: 'hidden' },
            '#fo-receipt, #fo-receipt *': { visibility: 'visible' },
            '#fo-receipt': { position: 'absolute', left: 0, top: 0, width: '80mm', padding: '4mm', color: '#000', background: '#fff' },
            '.MuiDialog-container, .MuiPaper-root': { boxShadow: 'none', background: '#fff' },
          },
        }} />
      )}
      <DialogContent>
        {h && detail && (
          <Box id="fo-receipt" sx={{ fontSize: 13, fontFamily: 'ui-monospace, Consolas, monospace' }}>
            <Typography sx={{ fontWeight: 700, textAlign: 'center', fontFamily: 'inherit' }}>{h.type.toUpperCase()}</Typography>
            <Typography sx={{ textAlign: 'center', fontSize: 12, fontFamily: 'inherit' }}>{h.docNo ?? `No. ${h.ctlNo}`}</Typography>
            {rule}
            {row('Date', `${h.date} ${h.time?.slice(0, 5) ?? ''}`)}
            {h.clientName && row('Member', h.clientName)}
            {h.refNo && row('Reference', h.refNo)}
            {rule}
            {detail.entries.map((e) => row(e.description ?? e.accountCode, e.debit ? `Dr ${money(e.debit)}` : `Cr ${money(e.credit)}`, `e${e.seq}`))}
            {rule}
            {row('TOTAL', money(h.total))}
            {detail.cash.length > 0 && (
              <>
                {rule}
                {detail.cash.map((c) => row(`${c.description} x ${c.qty}`, money(c.amount), `${c.kind}${c.description}`))}
              </>
            )}
            {h.explanation && (
              <>
                {rule}
                <Typography sx={{ fontSize: 12, fontFamily: 'inherit' }}>{h.explanation}</Typography>
              </>
            )}
            {rule}
            {row('Served by', user)}
            <Typography sx={{ textAlign: 'center', fontSize: 11, mt: 1.5, fontFamily: 'inherit' }}>Thank you</Typography>
          </Box>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        <Button variant="contained" disabled={!detail} onClick={() => window.print()}>Print</Button>
      </DialogActions>
    </Dialog>
  )
}
