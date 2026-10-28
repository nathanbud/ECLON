import { Box, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material'
import type { ReactNode } from 'react'
import { appBackground, glass } from './glass'

const isMac = /Mac|iPhone|iPad/.test(navigator.platform)
const MOD = isMac ? '⌘' : 'Ctrl+'

const SHORTCUTS: { area: string; rows: [string, string][] }[] = [
  {
    area: 'Everywhere',
    rows: [
      ['H', 'Go to the main dashboard. Does nothing while you type, hold Ctrl, Cmd, Alt or Shift, or have a dialog or menu open. Asks first if there is unsaved work.'],
      [`${MOD}K`, 'Jump to the search box in the title bar.'],
    ],
  },
  {
    area: 'Client Profile',
    rows: [
      ['Alt+C', 'Open the Client Profile menu in the title bar.'],
      [`${MOD}F`, 'Find a client.'],
      [`${MOD}E`, 'Edit the open profile (when you may edit it and no change is waiting).'],
      [`${MOD}S`, 'Save the changes for approval.'],
      ['Esc', 'Cancel editing.'],
    ],
  },
  {
    area: 'Front Office',
    rows: [
      ['Alt+F', 'Open the Front Office menu in the title bar.'],
      [`${MOD}1 to ${MOD}9`, 'Go to the first to ninth tab.'],
      [`${MOD}F`, 'Find a transaction.'],
      [`${MOD}N`, 'Start a new draft.'],
      [`${MOD}D`, 'Copy the open transaction to a new draft.'],
      [`${MOD}S`, 'Save the draft.'],
      [`${MOD}B`, 'Balance the entry; with Shift, balance it with cash.'],
      [`${MOD}Delete`, 'Delete the draft.'],
      ['Esc', 'Cancel the open draft (when not typing).'],
      ['Page Up / Page Down', 'Move to the previous or next transaction in the list (when not typing).'],
    ],
  },
]

const RULES: [string, string][] = [
  ['Posted money entries never change', 'A mistake is fixed with a reversal and a corrected entry, never by editing. Every entry balances (debits equal credits) before it posts.'],
  ['Maker and checker', 'The person who asks is never the person who approves. This holds for profile changes, new members, closings, reactivations, journals, loans, purchases and stock adjustments.'],
  ['Everything is audited', 'Who, when, what changed (before and after) and why. Administrators read it under Settings > Audit Log. Passwords, fingerprint data and other secrets are never written to it.'],
  ['Nothing is deleted', 'A member is closed, not removed; a certificate, loan or voucher is reversed or marked, not erased.'],
  ['The old database is protected', 'The application only adds or updates the few things it is allowed to, and does so through limited database rights.'],
]

const WHERE: [string, string][] = [
  ['Cash deposits and withdrawals', 'Only in the Front Office, on the Member Accounts tab: savings deposits and withdrawals, share capital payments and withdrawals, loan payments, and time deposit placement, interest withdrawal and pre-termination. A member\'s profile only shows the accounts.'],
  ['New loan applications', 'Only from the Loan Application tile. The member\'s Loans tab shows loans and their profile.'],
  ['Members', 'Client Profile: Info (Membership, Personal, Business / Employment, Education, Contacts & IDs, Spouse Info), Accounts (Share Capital, Savings, Time Deposits, Loans, Billing, Pledges / Insurances) and Other. Register a new member (the profile opens blank and in edit mode), close, or reactivate one from there; an officer other than the requester approves under Approvals.'],
  ['Registering many members', 'Advanced mode, for administrators: on the Client Profile finder screen choose Advanced mode: import a list. Upload a CSV or Excel file (download the blank Excel list: its Guide sheet explains each column), see every row checked, and send the valid ones. A Membership Officer other than the uploader approves the whole list under Approvals, which opens the members. Photos and fingerprints are added afterwards on each member.'],
  ['Member photo', 'The camera icon on the photo, shown only in edit mode, to people with both the edit-client and edit-client-images permissions. Photos are added, never overwritten.'],
  ['Fingerprints', 'The Fingerprint tag under the photo. The member\'s consent comes first and can be withdrawn, which erases every fingerprint. Until a scanner is connected a simulator is used for testing only.'],
  ['Loan Setup', 'Settings > Loan Setup opens in a window of its own. Tabs: loan types, purposes, collateral, co-maker holds and amortization. Opening a loan type shows Setup and COA Links; an administrator can change, add or remove its links to the chart of accounts there (principal, interest, penalty and fees), with a reason, audited. New postings use the new account.'],
  ['Settings', 'Administrator controls: users and permissions, the audit log, membership policy, approval workflows, documents, loan setup and the chart of accounts.'],
]

const DOCS: [string, string][] = [
  ['membership-lifecycle-map.md', 'Registering, closing and reactivating members; the member number check digit.'],
  ['fingerprint-design.md', 'How fingerprints are captured, protected and matched, and what is still a stand-in.'],
  ['loan-posting-map.md', 'How a loan is released and paid in the old ledger.'],
  ['share-payment-posting-map.md, share-withdrawal-posting-map.md', 'Share capital payments and withdrawals.'],
  ['savings-posting-map.md, savings-interest-map.md', 'Savings deposits, withdrawals and quarterly interest.'],
  ['time-deposit-map.md, time-deposit-accrual-map.md', 'Time deposits: placement, interest, payout, renewal and pre-termination.'],
  ['dividend-map.md', 'The annual dividend and patronage refund.'],
  ['cheque-receipt-posting-map.md, bank-cash-posting-map.md, voucher-payout-map.md', 'Cheques, bank transfers and voucher payouts.'],
  ['procurement-design.md, inventory-design.md, pos-design.md, fixed-assets-design.md', 'Purchasing, stock, point of sale and fixed assets.'],
  ['document-management-design.md, workflow-design.md, reporting-design.md', 'Documents, approval workflows and reports.'],
  ['postgres-legacy-load.md', 'Copying the old data into PostgreSQL.'],
]

function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Box sx={(t) => ({ p: 2, mb: 2, ...glass(t) })}>
      <Typography component="h2" sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 1 }} color="text.secondary">{title}</Typography>
      {children}
    </Box>
  )
}

function Pairs({ rows, head }: { rows: [string, string][]; head: [string, string] }) {
  return (
    <Table size="small" aria-label={head[0]}>
      <TableHead><TableRow><TableCell sx={{ width: '32%' }}>{head[0]}</TableCell><TableCell>{head[1]}</TableCell></TableRow></TableHead>
      <TableBody>
        {rows.map(([a, b]) => (
          <TableRow key={a}><TableCell sx={{ fontWeight: 600, verticalAlign: 'top' }}>{a}</TableCell><TableCell>{b}</TableCell></TableRow>
        ))}
      </TableBody>
    </Table>
  )
}

/** Settings > Documentation: the keyboard shortcuts and a short guide to how the system works and where things are. */
export default function DocumentationPage() {
  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 960, mx: 'auto' }}>
        <Typography component="h1" sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em', mb: 2 }}>Documentation</Typography>

        <Card title="Keyboard shortcuts">
          {SHORTCUTS.map((g) => (
            <Box key={g.area} sx={{ mb: 1.5 }}>
              <Typography sx={{ fontWeight: 700, fontSize: 14, mb: 0.5 }}>{g.area}</Typography>
              <Table size="small" aria-label={`${g.area} shortcuts`}>
                <TableBody>
                  {g.rows.map(([key, what]) => (
                    <TableRow key={key}>
                      <TableCell sx={{ width: '26%', whiteSpace: 'nowrap' }}><Box component="kbd" sx={{ fontFamily: 'inherit', fontSize: 12.5, px: 0.75, py: 0.25, border: 1, borderColor: 'divider', borderRadius: '5px' }}>{key}</Box></TableCell>
                      <TableCell>{what}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Box>
          ))}
          <Typography sx={{ fontSize: 12.5 }} color="text.secondary">On a Mac, Ctrl is the Command key (⌘).</Typography>
        </Card>

        <Card title="Rules the system follows"><Pairs rows={RULES} head={['Rule', 'What it means']} /></Card>
        <Card title="Where to do things"><Pairs rows={WHERE} head={['Task', 'Where']} /></Card>
        <Card title="Design notes in the project">
          <Typography sx={{ fontSize: 13, mb: 1 }} color="text.secondary">These files are in the project's documentation folder. They record how each part of the old system was read and how the new code reproduces it.</Typography>
          <Pairs rows={DOCS} head={['File', 'About']} />
        </Card>
      </Box>
    </Box>
  )
}
