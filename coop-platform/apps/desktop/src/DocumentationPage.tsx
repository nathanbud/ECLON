import { Box, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material'
import type { ReactNode } from 'react'
import { appBackground, glass } from './glass'

const isMac = /Mac|iPhone|iPad/.test(navigator.platform)
const MOD = isMac ? '⌘' : 'Ctrl+'

const SHORTCUTS: { area: string; rows: [string, string][] }[] = [
  {
    area: 'Everywhere',
    rows: [
      ['V', 'Start or stop listening for a voice command (the microphone in the title bar does the same). Esc stops it. Like H and M, it does nothing while you type or have a dialog open.'],
      ['M', 'Open or close the menu of screens (the logo at the top left does the same). Esc, or choosing a screen, closes it. Like H, it does nothing while you type or have a dialog open.'],
      ['H', 'Go to the main dashboard. Does nothing while you type, hold Ctrl, Cmd, Alt or Shift, or have a dialog or menu open. Asks first if there is unsaved work.'],
      [`${MOD}K`, 'Jump to the search box in the title bar.'],
    ],
  },
  {
    area: 'Client Profile',
    rows: [
      ['Alt+C', 'Open the Client Profile menu in the title bar.'],
      [`${MOD}F`, 'Find a client.'],
      ['I, A, O', 'Open Member info, Accounts or Other in the profile (the first section of each). Does nothing while you type, hold Ctrl, Alt or Shift, or have a dialog open. The key is shown on each entry of the menu.'],
      [`${MOD}N`, 'New: register a new member (when you may edit clients and are not already editing). The New Client button under the finder does the same.'],
      [`${MOD}E`, 'Edit the open profile (when you may edit it and no change is waiting).'],
      [`${MOD}S`, 'Save the changes. A Membership Officer’s changes apply at once and are logged; anyone else’s are sent to a Membership Officer for approval.'],
      ['Esc', 'Cancel editing.'],
      [`${MOD}${isMac ? '⇧' : 'Shift+'}C`, 'Close Membership: ask to close the open member (when you may edit clients and the member is not closed).'],
    ],
  },
  {
    area: 'Front Office',
    rows: [
      [`${MOD}1 to ${MOD}9`, 'Go to the first to ninth area of the window (Teller, Summary, Cash, and so on). A tab with an entry form opens with a new entry ready.'],
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
  ['Finding your way around', 'The menu on the left is on every screen and lists only what your roles allow, grouped by what you are doing: My work, Members, Teller and cash, Loans, Accounting, Purchasing and sales, Reports, Compliance and records, Administration. The arrows at its top fold it to icons. Home shows the same screens as tiles, and Ctrl+K searches them (and members). All three read one list of screens, so they always agree. A tab inside a screen, such as Accounting > Debit/credit memos, has its own entry.'],
  ['Waiting for you', 'The first entry of the menu, and the card at the top of Home, list everything that needs your approval or action: client profile changes and member requests (for Membership Officers), journals and memos to review or post (accountants), purchase requests and orders (purchase approvers), stock adjustments (stock approvers) and extra workflow steps. It never lists your own work, because someone else must decide it. Each item opens the screen that decides it, on the right tab; profile changes, member requests and workflow steps are decided on the same page, below the list.'],
  ['Debit/credit memos', 'Menu > Accounting > Debit/credit memos, or the Memos tab of Accounting (accountants and administrators): an adjustment or transfer that changes members’ savings or share capital accounts and ledger accounts in one balanced entry, for example from a deceased member to a beneficiary. A debit takes money out of a member’s account, a credit puts it in. It must give the reason and who authorised it, and it can never take a member’s account below zero. The person who makes it, the person who approves it and the person who posts it must be three different people. A posted memo is reversed by a new memo, never edited. Loans are not included yet.'],
  ['Where an entry came from', 'In the details of a Savings, Share Capital, Time Deposit or Loan account, click a ledger row (or press Enter on it) to see the transaction behind it: the header, the general ledger and subsidiary ledger lines it wrote (with their totals), the cheques it carried, the source reference and the description. It only opens transactions that are in that member’s own ledger, and nothing in it can be changed.'],
  ['Voice commands', 'Press V or the microphone in the title bar (the first time you are told what it is for), then say what you want: “open audit log”, “go home”, “menu”, “find client Santos” (the name is typed into the finder; press Enter to open the member), and, with a member open, “open savings”, “share capital”, “loans”, “member info”, “accounts”. The microphone turns itself off after a command, after a few quiet seconds, or on Esc. Voice only opens screens and searches: it never saves, approves or posts anything, and it can only open screens your menu shows. Speech is turned into words on this computer by a small model that comes with the app; nothing is sent over the network and no audio is kept. It recognizes English; a misheard word is ignored rather than guessed, so say it again. If the microphone button is grey, the model is not installed (run scripts/fetch-voice-model.ps1 and restart the app).'],
  ['Editing a profile', 'A person with edit permission changes a client in edit mode and saves; the change is sent to a Membership Officer, who is not the sender, to approve or reject. A person who is a Membership Officer does not need a second person: their changes are applied at once when they save. Either way every changed field is written to the audit log (Settings > Audit Log) with who made it, the old and new value and, for an officer’s own edit, a note that no second approval was needed.'],
  ['Beneficiaries', 'Client Profile > Info > Beneficiaries (right of Spouse Info). A person with edit permission adds, changes or removes a beneficiary by sending a request; a Membership Officer other than the sender approves or rejects it on the Approvals tile, and only then does the list change. A removal needs a reason, and the removed details are kept in the Audit Log.'],
  ['Finding a client', 'A search by name lists active clients only, so it is quicker. Turn on Include inactive in Find Client to see dormant and closed ones. A member ID or reference number finds the client whatever the status. The title-bar search and the pickers in Front Office, Loan Application and co-makers list active clients only. A word that starts a last or first name is found at once; a word from the middle of a name or account name takes a moment longer. When more than a page matches, the list says so instead of counting them.'],
  ['Posted money entries never change', 'A mistake is fixed with a reversal and a corrected entry, never by editing. Every entry balances (debits equal credits) before it posts.'],
  ['Maker and checker', 'The person who asks is never the person who approves. This holds for profile changes, new members, closings, reactivations, journals, loans, purchases and stock adjustments.'],
  ['Everything is audited', 'Who, when, what changed (before and after) and why. Administrators read it under Settings > Audit Log. Passwords, fingerprint data and other secrets are never written to it.'],
  ['Nothing is deleted', 'A member is closed, not removed; a certificate, loan or voucher is reversed or marked, not erased.'],
  ['The old database is protected', 'The application only adds or updates the few things it is allowed to, and does so through limited database rights.'],
]

const WHERE: [string, string][] = [
  ['Cash deposits and withdrawals', 'Only in the Front Office, on the Teller tab: savings deposits and withdrawals, share capital payments and withdrawals, loan payments, and time deposit placement, interest withdrawal and pre-termination. A member\'s profile only shows the accounts.'],
  ['New loan applications', 'Only from the Loan Application tile. The member\'s Loans tab shows loans and their profile.'],
  ['Members', 'Client Profile: Info (Membership, Personal, Business / Employment, Education, Contacts & IDs, Spouse Info), Accounts (Share Capital, Savings, Time Deposits, Loans, Billing, Pledges / Insurances) and Other. Register a new member with Client Profile menu > New (Ctrl+N; the form opens blank), close or reactivate one from the profile; an officer other than the requester approves under Approvals.'],
  ['Registering many members', 'Advanced mode, for administrators: on the Client Profile finder screen choose Advanced mode: import a list. Upload a CSV or Excel file (download the blank Excel list: its Guide sheet explains each column), see every row checked, and send the valid ones. A Membership Officer other than the uploader approves the whole list under Approvals, which opens the members. Photos and fingerprints are added afterwards on each member.'],
  ['Member profile layout', 'Client Profile has a dark panel on the left (photo, name, the fingerprint, signature and privacy-notice tags and the main menu: Member info, Accounts, Other; pressing one opens its sections as a sub-menu, for example Membership, Personal and Business / Employment under Member info), the open section’s name across the top with member type, department, date opened and, beside them, the account status and alert level, under Accounts, four balance cards (share capital, savings, loans owed, time deposits; press one to open its section; they are not shown on the other menus) and the section itself below. Nothing was removed from the old layout; the same fields are in the same sections.'],
  ['Member photo', 'The camera icon on the photo, shown only in edit mode, to people with both the edit-client and edit-client-images permissions. Photos are added, never overwritten.'],
  ['Member signature', 'The Signature tag beside Fingerprint in the left panel of the profile. Pressing it shows the signature on file. People with both the edit-client and edit-client-images permissions also get Capture signature (or Replace signature): the member signs on the pad with a mouse, finger or pen, or a scanned signature is chosen from a file, then Save signature. Signatures are added, never overwritten; the newest is shown.'],
  ['Fingerprints', 'The Fingerprint tag under the photo. The member\'s consent comes first and can be withdrawn, which erases every fingerprint. Until a scanner is connected a simulator is used for testing only.'],
  ['Loan Setup', 'Settings > Loan Setup opens in a window of its own. Tabs: loan types, purposes, collateral, co-maker holds and amortization. Opening a loan type shows Setup and COA Links; an administrator can change, add or remove its links to the chart of accounts there (principal, interest, penalty and fees), with a reason, audited. New postings use the new account.'],
  ['Backup', 'Settings > System > Backup (administrators): Back up now saves every table the application owns and the fingerprint key into a dated folder on the server; the list shows past backups and marks one Incomplete if the dump or the key is missing. The old system’s tables are not in it. Copy the dump and the key together to another computer. Restoring is done by hand from the dump, not from this screen.'],
  ['Database connection', 'Sign-in screen > Connection: pick which server the app talks to (a list kept on this computer) and see which database it works on. Settings > System > Database connection (SUPERADMIN only): save connections (name, host, port, database, branch), then Use this connection to move the server to one; everyone signs in again. The database user and password stay in the server’s settings.'],
  ['Sign-in screen wording', 'Settings > System > Sign-in screen (SUPERADMIN only): the cooperative’s logo, name and tagline shown on the left of the sign-in screen, with a live preview. Everyone sees them, including people not signed in yet.'],
  ['Loan worksheet', 'Client Profile > Loans > open a loan > Worksheet as of (a date), then Worksheet PDF or Worksheet Excel: the Loan Interest and Penalty Computation Worksheet. Each download is recorded in the audit log and the export log. The penalty columns are not yet confirmed against a late loan.'],
  ['CIC submission', 'Menu > Compliance and records > CIC submission, or Settings > Credit reporting (administrators and accountants): make a batch, generate it from the books at the cut-off date, read the findings, have another person approve it, download the file and record the CIC’s reference, result and reconciliation. Approval stays off until the file mapping is confirmed by a file the CIC accepts.'],
  ['Data privacy','Menu > Compliance and records > Data privacy, or Settings > Data privacy (administrators and data protection officers): log a request about someone’s data, check identity, hand over the member data file, erase fingerprints; incidents with a 72-hour reporting deadline; sharing, assessments, notices, retention and classification; the export log. The privacy notice is recorded on the member profile. Starting retention rules and classifications are drafts to confirm against the Data Privacy Act.'],
  ['Audit log and its seals', 'Menu > Compliance and records > Audit Log, or Settings > Audit Log (administrators): every change with who, what, when, where (address and device), why, before and after. The banner shows how far the log is sealed; the log is sealed every night, each seal chained to the one before, and Check the seals reports any entry that was changed or removed. Nothing in the app can edit or delete an audit entry.'],
  ['Settings', 'Administrator controls: users and permissions, the audit log, membership policy, approval workflows, documents, loan setup and the chart of accounts.'],
]

const DOCS: [string, string][] = [
  ['membership-lifecycle-map.md', 'Registering, closing and reactivating members; the member number check digit.'],
  ['fingerprint-design.md', 'How fingerprints are captured, protected and matched, and what is still a stand-in.'],
  ['loan-worksheet-and-document-generation.md', 'The loan interest and penalty worksheet, and how PDFs (JasperReports) and Excel files (Apache POI) are made.'],
  ['loan-posting-map.md', 'How a loan is released and paid in the old ledger.'],
  ['share-payment-posting-map.md, share-withdrawal-posting-map.md', 'Share capital payments and withdrawals.'],
  ['savings-posting-map.md, savings-interest-map.md', 'Savings deposits, withdrawals and quarterly interest.'],
  ['time-deposit-map.md, time-deposit-accrual-map.md', 'Time deposits: placement, interest, payout, renewal and pre-termination.'],
  ['dividend-map.md', 'The annual dividend and patronage refund.'],
  ['dividend-spec.md', 'The interest on share capital and patronage refund specification, and what the Dividends screen does with it.'],
  ['debit-credit-memo-map.md', 'How the old system posted debit/credit memos, and what the new Memos screen does.'],
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
