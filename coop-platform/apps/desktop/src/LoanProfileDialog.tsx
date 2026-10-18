import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Skeleton, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, TextField, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { getLoanProfile } from './api'

const money = (n: number | null | undefined) => (n == null ? '' : n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }))

/** One read-only field, laid out like the old screen's boxes. */
function F({ label, value, align, bold }: { label: string; value: ReactNode; align?: 'right'; bold?: boolean }) {
  return (
    <TextField size="small" label={label} value={value ?? ''} fullWidth slotProps={{ input: { readOnly: true }, htmlInput: { style: { textAlign: align, fontVariantNumeric: 'tabular-nums', fontWeight: bold ? 700 : undefined } } }} />
  )
}

/**
 * The old "Loans Profile" screen for one loan, read only: client header, then Setup / Advanced on the left and Due Dates / Co-Makers /
 * Collateral / Others on the right. Nothing here can be edited; creating a loan application is a later step.
 */
export default function LoanProfileDialog({ branchCode, clientId, typeCode, refNo, onClose }: { branchCode: number; clientId: number; typeCode: number; refNo: number; onClose: () => void }) {
  const [left, setLeft] = useState(0)
  const [right, setRight] = useState(0)
  const { data, error, isLoading } = useQuery({ queryKey: ['loan-profile', branchCode, clientId, typeCode, refNo], queryFn: () => getLoanProfile(branchCode, clientId, typeCode, refNo) })
  const h = data?.header
  const s = data?.setup
  return (
    <Dialog open onClose={onClose} maxWidth="lg" fullWidth>
      <DialogTitle>Loans Profile{s ? ` · ${s.loanType ?? ''} #${s.refNo}` : ''}</DialogTitle>
      <DialogContent>
        {error && <Alert severity="error">{(error as Error).message}</Alert>}
        {isLoading && <Skeleton variant="rounded" height={320} />}
        {h && s && data && (
          <Box sx={{ display: 'grid', gap: 2, pt: 1 }}>
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '2fr 1fr' }, gap: 2 }}>
              <Box sx={{ display: 'grid', gridTemplateColumns: '2fr 2fr 2fr 1fr', gap: 1 }}>
                <Box sx={{ gridColumn: '1 / -1' }}><F label="Client ID" value={h.clientNo} /></Box>
                <F label="Last name" value={h.lastName} /><F label="First name" value={h.firstName} /><F label="Middle name" value={h.middleName} /><F label="Suffix" value={h.suffix} />
                <Box sx={{ gridColumn: '1 / 2' }}><F label="Date opened" value={h.dateOpened} /></Box>
              </Box>
              <Box sx={{ display: 'grid', gap: 1 }}>
                <F label="Acct type" value={h.accountType} /><F label="Joint type" value={h.jointType} /><F label="Client type" value={h.clientType} /><F label="Acct status" value={h.accountStatus} />
              </Box>
            </Box>

            {!data.scheduleCheck.checked ? null : (
              <Alert severity={data.scheduleCheck.matches ? 'success' : 'warning'} sx={{ py: 0 }}>{data.scheduleCheck.note}</Alert>
            )}

            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '1.2fr 1fr' }, gap: 2 }}>
              <Box>
                <Tabs value={left} onChange={(_, v) => setLeft(v)} sx={{ mb: 1 }}><Tab label="Setup" /><Tab label="Advanced" /></Tabs>
                {left === 0 && (
                  <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 1.25 }}>
                    <Box sx={{ display: 'grid', gap: 1.25, alignContent: 'start' }}>
                      <F label="Loan type" value={s.loanType} />
                      <F label="Classification" value={s.classification} />
                      <F label="Ref. num" value={s.refNo} />
                      <F label="Principal" value={money(s.principal)} align="right" bold />
                      <F label="Balance" value={money(s.balance)} align="right" />
                      <F label="Loan date" value={s.loanDate} />
                      <F label="Grace period (payments)" value={s.termGraceDays} align="right" />
                      <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 1 }}><F label="Term" value={s.terms} align="right" /><F label="Unit" value={s.termUnit} /></Box>
                      <F label="Pymt. mode" value={s.paymentMode} />
                      <F label="Status" value={s.status} />
                    </Box>
                    <Box sx={{ display: 'grid', gap: 1.25, alignContent: 'start' }}>
                      <F label="Interest % p.a." value={s.interestRate.toFixed(5)} align="right" bold />
                      <F label="Based on" value={s.basedOn} />
                      <F label="Diminish" value={s.diminish} />
                      <F label="Pymt. mode (interest)" value={s.paymentMode} />
                      <F label="Grace period (payments)" value={s.principalGraceDue} align="right" />
                      <F label="Prepaid (payments)" value={s.prepaidPayments} align="right" />
                      <F label="Penalty % p.a." value={s.penaltyRate == null ? '' : s.penaltyRate.toFixed(2)} align="right" bold />
                      <F label="After maturity % p.a." value={s.penaltyAfterMaturity == null ? '' : s.penaltyAfterMaturity.toFixed(2)} align="right" />
                      <F label="Penalty computed" value={s.penaltyComputed} />
                      <F label="Penalty grace (days)" value={s.penaltyGraceDays} align="right" />
                      <F label="Insurance" value={money(s.insurance)} align="right" bold />
                    </Box>
                  </Box>
                )}
                {left === 1 && (
                  <Box sx={{ display: 'grid', gap: 1.25, maxWidth: 420 }}>
                    <F label="Loan class" value={s.loanClass} />
                    <F label="Purpose" value={s.purpose} />
                    <Typography sx={{ fontSize: 12 }} color="text.secondary">The other advanced settings of the old screen are not shown yet.</Typography>
                  </Box>
                )}
              </Box>

              <Box>
                <Tabs value={right} onChange={(_, v) => setRight(v)} variant="scrollable" scrollButtons="auto" sx={{ mb: 1 }}>
                  <Tab label="Due Dates" /><Tab label="Co-Makers" /><Tab label="Collateral" /><Tab label="Others" />
                </Tabs>
                {right === 0 && (
                  <Box sx={{ display: 'grid', gap: 1.25 }}>
                    <F label="Due date option" value={s.dueDateOption} />
                    <F label="1st interest due date" value={s.firstInterestDue} />
                    <F label="1st principal due date" value={s.firstPrincipalDue} />
                    <F label="Maturity date" value={s.maturity} />
                    <F label="Collection type" value={s.collectionType} />
                    <Typography sx={{ fontSize: 12, fontWeight: 700 }} color="text.secondary">Pledges</Typography>
                    <Table size="small" aria-label="Pledges">
                      <TableHead><TableRow><TableCell>Pledge profile</TableCell><TableCell align="right">Amount</TableCell><TableCell>Client pledge</TableCell></TableRow></TableHead>
                      <TableBody>
                        {data.pledges.map((p, i) => <TableRow key={i}><TableCell>{p.profile}</TableCell><TableCell align="right">{money(p.amount)}</TableCell><TableCell>{p.clientPledge ?? ''}</TableCell></TableRow>)}
                        {data.pledges.length === 0 && <TableRow><TableCell colSpan={3} sx={{ color: 'text.secondary' }}>No pledges.</TableCell></TableRow>}
                      </TableBody>
                    </Table>
                  </Box>
                )}
                {right === 1 && (
                  <Table size="small" aria-label="Co-makers">
                    <TableHead><TableRow><TableCell>Name</TableCell><TableCell>Address</TableCell></TableRow></TableHead>
                    <TableBody>
                      {data.coMakers.map((c, i) => <TableRow key={i}><TableCell>{c.name ?? `Client ${c.clientId}`}</TableCell><TableCell>{c.address ?? ''}</TableCell></TableRow>)}
                      {data.coMakers.length === 0 && <TableRow><TableCell colSpan={2} sx={{ color: 'text.secondary' }}>No co-makers.</TableCell></TableRow>}
                    </TableBody>
                  </Table>
                )}
                {right === 2 && (
                  <Box sx={{ display: 'grid', gap: 1.25 }}>
                    <F label="Collateral type" value={data.collateral?.type} />
                    <F label="Amount" value={money(data.collateral?.amount)} align="right" />
                    <F label="Description" value={data.collateral?.description} />
                  </Box>
                )}
                {right === 3 && <F label="Remarks" value={data.remarks} />}
              </Box>
            </Box>
          </Box>
        )}
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
    </Dialog>
  )
}
