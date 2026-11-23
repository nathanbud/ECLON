import type { ReactNode } from 'react'
import {
  FiBarChart2, FiBook, FiBookOpen, FiBriefcase, FiClipboard, FiEdit, FiCreditCard, FiDatabase, FiDollarSign, FiDownloadCloud, FiFilePlus, FiFileText, FiGitMerge, FiImage, FiInbox, FiLayers, FiList, FiMoon, FiPercent, FiRepeat,
  FiSend, FiSettings, FiShield, FiShoppingCart, FiSliders, FiPieChart, FiTrendingUp, FiUserCheck, FiUserPlus, FiUsers, FiLock, FiCalendar, FiHome,
  FiClock, FiToggleRight, FiCheckSquare, FiAlertTriangle, FiPackage, FiMapPin, FiTarget,
} from 'react-icons/fi'
import type { Me } from '../api'
import type { View } from '../Dashboard'
import type { Tint } from '../theme'
import { featureOn, type Feature } from '../ui/features'

/**
 * Every screen of the app, once. The side menu, the home tiles and the search all read this list, so they can never disagree about what exists, what it is called or who may open it.
 * To add a screen, add it here (and to the switch in App.tsx); nothing else needs a list.
 */

/** The business areas the screens are grouped into: what a person is doing, not which department owns it. */
export type DomainId = 'inbox' | 'members' | 'teller' | 'loans' | 'accounting' | 'commerce' | 'reports' | 'compliance' | 'admin'

export const DOMAINS: Record<DomainId, { label: string; color: Tint }> = {
  inbox: { label: 'My work', color: 'orange' },
  members: { label: 'Members', color: 'orange' },
  teller: { label: 'Teller and cash', color: 'green' },
  loans: { label: 'Loans', color: 'teal' },
  accounting: { label: 'Accounting', color: 'indigo' },
  commerce: { label: 'Purchasing and sales', color: 'blue' },
  reports: { label: 'Reports', color: 'indigo' },
  compliance: { label: 'Compliance and records', color: 'purple' },
  admin: { label: 'Administration', color: 'gray' },
}

/** The order the groups appear in. */
export const DOMAIN_ORDER: DomainId[] = ['inbox', 'members', 'teller', 'loans', 'accounting', 'commerce', 'reports', 'compliance', 'admin']

/** Who may open a screen. The server checks again on every call; this only decides what a person is shown. */
export type Who =
  | 'anyone' | 'admin' | 'superadmin' | 'accountant' | 'books' | 'auditlog' | 'privacy' | 'documents' | 'purchasing' | 'inventory' | 'pos' | 'assets' | 'settings' | 'memberrecords' | 'officer'

export interface Screen {
  id: string
  label: string
  /** One line saying what it is for; shown on tiles, in the menu's tooltip and in the search. */
  note: string
  domain: DomainId
  icon: ReactNode
  view: View
  /** The tab to open inside the screen, by the tab's name. */
  tab?: string
  who: Who
  /** Shown as a tile on the home page. The rest are reached from the side menu and the search. */
  tile?: boolean
  keywords?: string
}

const role = (me: Me, r: string) => me.roles.some((x) => x.replace('ROLE_', '') === r)
const anyRole = (me: Me, rs: string[]) => rs.some((r) => role(me, r))

/** Whether this person may open a screen of this kind. */
export function can(me: Me, who: Who): boolean {
  switch (who) {
    case 'anyone': return true
    case 'admin': return me.admin
    case 'superadmin': return role(me, 'SUPERADMIN')
    case 'accountant': return me.admin || role(me, 'ACCOUNTANT')
    // the books read: accountants and administrators, and auditors, who change nothing
    case 'books': return me.admin || anyRole(me, ['ACCOUNTANT', 'AUDITOR'])
    case 'auditlog': return me.admin || role(me, 'AUDITOR')
    case 'privacy': return me.admin || role(me, 'DATA_PROTECTION_OFFICER')
    case 'documents': return me.admin || anyRole(me, ['ACCOUNTANT', 'DOCUMENT_CLERK'])
    case 'purchasing': return me.admin || anyRole(me, ['PURCHASER', 'PURCHASE_APPROVER', 'RECEIVER', 'ACCOUNTANT'])
    case 'inventory': return me.admin || anyRole(me, ['STOREKEEPER', 'STOCK_APPROVER', 'RECEIVER', 'PURCHASER', 'PURCHASE_APPROVER', 'ACCOUNTANT'])
    case 'pos': return me.admin || anyRole(me, ['POS_CASHIER', 'POS_SUPERVISOR', 'STOREKEEPER', 'ACCOUNTANT'])
    case 'assets': return me.admin || anyRole(me, ['ASSET_CUSTODIAN', 'ACCOUNTANT'])
    case 'settings': return me.admin || anyRole(me, ['ACCOUNTANT', 'DOCUMENT_CLERK', 'DATA_PROTECTION_OFFICER'])
    case 'memberrecords': return me.admin || me.canEdit || role(me, 'MEMBERSHIP_OFFICER')
    case 'officer': return me.admin || role(me, 'MEMBERSHIP_OFFICER')
  }
}

export const SCREENS: Screen[] = [
  // my work
  { id: 'waiting', label: 'Waiting for you', note: 'Everything that needs your approval or action', domain: 'inbox', icon: <FiInbox />, view: 'approvals', who: 'anyone', tile: true, keywords: 'approvals inbox pending review requests tasks todo' },

  // members
  { id: 'members', label: 'Member Profile', note: 'Find a member, see and change their record', domain: 'members', icon: <FiUsers />, view: 'members', who: 'anyone', tile: true, keywords: 'members members customers people profile search register beneficiaries' },
  { id: 'dormancy', label: 'Savings dormancy', note: 'Savings accounts due to go dormant, and reactivating dormant ones', domain: 'members', icon: <FiMoon />, view: 'dormancy', who: 'officer', keywords: 'dormant dormancy inactive savings reactivate' },
  { id: 'kycreview', label: 'KYC review', note: 'Members whose KYC is missing, incomplete, expired or due for review', domain: 'members', icon: <FiUserCheck />, view: 'kycreview', who: 'memberrecords', tile: true, keywords: 'kyc know your customer id expired review aml anti money laundering' },

  // teller and cash
  { id: 'businessday', label: 'Business Day', note: 'Start Day and End Day: the date postings carry, and the end-of-day checks', domain: 'teller', icon: <FiCalendar />, view: 'businessday', who: 'anyone', tile: true, keywords: 'start day end day open close business date eod sod end of day beginning of day cut-off' },
  { id: 'frontoffice', label: 'Front Office', note: 'Deposits, withdrawals, payments and the teller’s day', domain: 'teller', icon: <FiDollarSign />, view: 'frontoffice', who: 'anyone', tile: true, keywords: 'teller cash receipt disburse withdrawal deposit encashment cheque clearing drawer' },

  // loans
  { id: 'loans', label: 'Loan Module', note: 'Start loan applications and take them through Profiling, Deduction, Verification and Approval', domain: 'loans', icon: <FiBriefcase />, view: 'loans', who: 'anyone', tile: true, keywords: 'loan loans application applications apply borrow profiling deduction verify verification approve approval module' },
  { id: 'loanapp', label: 'New loan application', note: 'Find a member and start a loan application', domain: 'loans', icon: <FiFilePlus />, view: 'loanapp', who: 'anyone', keywords: 'loan apply new application borrow release' },

  // accounting
  { id: 'accounting', label: 'Accounting', note: 'Trial balance, statements, periods, banks and the year-end close', domain: 'accounting', icon: <FiBookOpen />, view: 'accounting', who: 'books', tile: true, keywords: 'accounting ledger books general' },
  { id: 'acc-tb', label: 'Trial balance', note: 'Balances of every ledger account', domain: 'accounting', icon: <FiBarChart2 />, view: 'accounting', tab: 'Trial balance', who: 'books', keywords: 'tb debit credit' },
  { id: 'acc-statements', label: 'Financial statements', note: 'Financial condition and statement of operations', domain: 'accounting', icon: <FiFileText />, view: 'accounting', tab: 'Statements', who: 'books', keywords: 'balance sheet income operations condition' },
  { id: 'acc-periods', label: 'Accounting periods', note: 'Open and close the books', domain: 'accounting', icon: <FiCalendar />, view: 'accounting', tab: 'Periods', who: 'books', keywords: 'period close month year lock' },
  { id: 'acc-banks', label: 'Banks', note: 'Bank cash and reconciliation', domain: 'accounting', icon: <FiCreditCard />, view: 'accounting', tab: 'Banks', who: 'books', keywords: 'bank reconcile deposit withdrawal cash' },
  { id: 'acc-yearend', label: 'Year-end close', note: 'Close a finished year into undivided net surplus', domain: 'accounting', icon: <FiLock />, view: 'accounting', tab: 'Year-end close', who: 'books', keywords: 'closing entry close books year end surplus' },
  { id: 'acc-budget', label: 'Budget', note: 'Annual budget per income and expense account, and budget versus actual', domain: 'accounting', icon: <FiTarget />, view: 'accounting', tab: 'Budget', who: 'books', keywords: 'budget budgets plan forecast variance versus actual target' },
  { id: 'backoffice', label: 'Back Office', note: 'General journal, debit/credit memos and penalty waivers', domain: 'accounting', icon: <FiEdit />, view: 'backoffice', who: 'accountant', tile: true, keywords: 'back office entries entry journal gj memo dcm debit credit adjustment waiver' },
  { id: 'acc-journals', label: 'General journal', note: 'General journal entries and their approval', domain: 'accounting', icon: <FiClipboard />, view: 'backoffice', tab: 'General journal', who: 'accountant', keywords: 'journal entry gj post approve' },
  { id: 'acc-memos', label: 'Debit/credit memos', note: 'Adjustments and transfers between members’ accounts', domain: 'accounting', icon: <FiRepeat />, view: 'backoffice', tab: 'Debit/credit memos', who: 'accountant', keywords: 'memo dcm debit credit transfer adjustment beneficiary' },
  { id: 'acc-waivers', label: 'Penalty waivers', note: 'Loan payments where less penalty was charged', domain: 'accounting', icon: <FiPercent />, view: 'backoffice', tab: 'Penalty waivers', who: 'accountant', keywords: 'penalty waiver loan' },
  { id: 'bo-disbursements', label: 'Disbursement vouchers', note: 'Cash and Check Vouchers: make, approve and post', domain: 'accounting', icon: <FiCreditCard />, view: 'backoffice', tab: 'Disbursements', who: 'accountant', keywords: 'disbursement voucher cash voucher check voucher cheque cv ckv payable' },
  { id: 'acc-dividends', label: 'Dividends', note: 'Yearly dividend on share capital and patronage refund', domain: 'accounting', icon: <FiPieChart />, view: 'dividends', who: 'accountant', tile: true, keywords: 'dividend patronage refund share capital year end' },
  { id: 'accounts', label: 'Chart of Accounts', note: 'Ledger accounts: add, rename, move and switch off', domain: 'accounting', icon: <FiBook />, view: 'accounts', who: 'anyone', tile: true, keywords: 'coa accounts ledger gl' },
  { id: 'assets', label: 'Fixed Assets', note: 'Asset register and depreciation', domain: 'accounting', icon: <FiTrendingUp />, view: 'assets', who: 'assets', tile: true, keywords: 'asset depreciation register disposal' },

  // purchasing and sales
  { id: 'procurement', label: 'Purchasing', note: 'Suppliers, requests, orders and receiving', domain: 'commerce', icon: <FiBriefcase />, view: 'procurement', who: 'purchasing', tile: true, keywords: 'purchase supplier order request receive payables' },
  { id: 'inventory', label: 'Inventory', note: 'Stock, movements, adjustments and counts', domain: 'commerce', icon: <FiList />, view: 'inventory', who: 'inventory', tile: true, keywords: 'stock warehouse products items count adjustment' },
  { id: 'pos', label: 'Point of Sale', note: 'Sell, take payment and handle returns', domain: 'commerce', icon: <FiShoppingCart />, view: 'pos', who: 'pos', tile: true, keywords: 'pos sale cashier shop store returns discount' },

  // reports
  { id: 'reports', label: 'Reports', note: 'Membership, loans, accounting, inventory and sales reports', domain: 'reports', icon: <FiBarChart2 />, view: 'reports', who: 'anyone', tile: true, keywords: 'report export csv print portfolio par delinquent' },
  { id: 'aging', label: 'Aging of receivables', note: 'Loans and receivables owed, aged by PAR or maturity, grouped and filtered', domain: 'reports', icon: <FiClock />, view: 'reports', tab: 'aging', who: 'books', keywords: 'aging receivable par past due delinquent maturity collector arrears' },
  { id: 'slrec', label: 'SL reconciliation', note: 'Each control account in the chart against the members’ accounts that post to it', domain: 'reports', icon: <FiCheckSquare />, view: 'reports', tab: 'sl-reconciliation', who: 'books', keywords: 'reconcile reconciliation control account subsidiary ledger sl gl difference balance savings share loans' },

  // compliance and records
  { id: 'cic', label: 'CIC submission', note: 'Credit information file for the Credit Information Corporation', domain: 'compliance', icon: <FiSend />, view: 'cic', who: 'accountant', tile: true, keywords: 'credit information corporation bureau submit file' },
  { id: 'privacy', label: 'Data privacy', note: 'Requests, incidents, sharing, notices and retention', domain: 'compliance', icon: <FiShield />, view: 'privacy', who: 'privacy', tile: true, keywords: 'privacy dpo data protection incident consent' },
  { id: 'audit', label: 'Audit Log', note: 'Every change made, who made it and why', domain: 'compliance', icon: <FiClipboard />, view: 'audit', who: 'auditlog', tile: true, keywords: 'audit trail log history integrity seal' },
  { id: 'documents', label: 'Documents', note: 'Scanned IDs, contracts and deeds, and who may read them', domain: 'compliance', icon: <FiFileText />, view: 'documents', who: 'documents', tile: true, keywords: 'documents files scans records' },

  // administration
  { id: 'settings', label: 'Settings', note: 'Administrator controls in one place', domain: 'admin', icon: <FiSettings />, view: 'settings', who: 'settings', tile: true, keywords: 'settings preferences configuration administrator' },
  { id: 'users', label: 'Users', note: 'Temporary passwords, levels, roles and permissions', domain: 'admin', icon: <FiUserCheck />, view: 'users', who: 'admin', keywords: 'users accounts passwords sign in roles membership officer' },
  { id: 'accounttypes', label: 'Account Types', note: 'Savings, deposit, share capital, receivable and payable types and their ledger accounts', domain: 'admin', icon: <FiLayers />, view: 'accounttypes', who: 'admin', keywords: 'sl types subsidiary ledger savings time deposit share capital interest rate minimum balance gl control' },
  { id: 'loansetup', label: 'Loan Setup', note: 'Loan types, purposes, collateral and co-maker holds', domain: 'admin', icon: <FiSliders />, view: 'loansetup', who: 'admin', keywords: 'loan types purposes collateral setup' },
  { id: 'amortization', label: 'Amortization', note: 'How each schedule is worked out, with a calculator', domain: 'admin', icon: <FiPercent />, view: 'amortization', who: 'admin', keywords: 'amortization schedule calculator interest' },
  { id: 'workflows', label: 'Approval workflows', note: 'Extra approvals and the amounts they start at', domain: 'admin', icon: <FiGitMerge />, view: 'workflows', who: 'accountant', keywords: 'workflow approval limits steps' },
  { id: 'slcontrols', label: 'SL Controls', note: 'Which accounts open by themselves for a new member', domain: 'admin', icon: <FiToggleRight />, view: 'slcontrols', who: 'admin', keywords: 'sl controls accounts open automatically new member registration savings share capital funds default' },
  { id: 'memberdefaults', label: 'Member defaults', note: 'Default department and office for new members, and the member types registered', domain: 'admin', icon: <FiUserPlus />, view: 'memberdefaults', who: 'admin', keywords: 'member defaults default department office member type registration regular associate' },
  { id: 'membershippolicy', label: 'Membership policy', note: 'Rules for returning members', domain: 'admin', icon: <FiUserPlus />, view: 'membershippolicy', who: 'admin', keywords: 'membership policy reactivation share capital' },
  { id: 'offices', label: 'Offices', note: 'The main office and satellites members and users belong to', domain: 'admin', icon: <FiMapPin />, view: 'offices', who: 'admin', keywords: 'offices office restrik main office satellite branch location organization' },
  { id: 'departments', label: 'Departments', note: 'The main office, satellites and buildings members, users and the books belong to', domain: 'admin', icon: <FiLayers />, view: 'departments', who: 'admin', keywords: 'departments department dept main office satellite building consolidated tin organization' },
  { id: 'branding', label: 'Sign-in screen', note: 'The cooperative’s logo, name and tagline', domain: 'admin', icon: <FiImage />, view: 'branding', who: 'superadmin', keywords: 'logo brand name sign in' },
  { id: 'features', label: 'Features', note: 'Which areas this cooperative uses: savings, time deposits, loans, the store and the rest', domain: 'admin', icon: <FiToggleRight />, view: 'features', who: 'admin', keywords: 'features modules switch on off enable disable savings time deposits loans pos inventory purchasing assets dividends passbook cic products offered' },
  { id: 'connections', label: 'Database connection', note: 'Which database this server works on, and the saved connections', domain: 'admin', icon: <FiDatabase />, view: 'connections', who: 'superadmin', keywords: 'database connection server host port branch office switch' },
  { id: 'startersetup', label: 'Starter setup', note: 'Load the standard lists a new database needs: account statuses, terms, payment modes, genders and the rest', domain: 'admin', icon: <FiPackage />, view: 'startersetup', who: 'admin', keywords: 'starter setup standard lists reference lookup new database load seed account status gender civil status terms' },
  { id: 'errors', label: 'Error log', note: 'Unexpected failures with their technical cause and what to do, found by the reference a person quotes', domain: 'admin', icon: <FiAlertTriangle />, view: 'errors', who: 'admin', keywords: 'error errors failure problem reference log trace exception system' },
  { id: 'backup', label: 'Backup', note: 'Back up the application’s data and see past backups', domain: 'admin', icon: <FiDownloadCloud />, view: 'backup', who: 'admin', keywords: 'backup restore dump copy data safe' },
  { id: 'documentation', label: 'Documentation', note: 'Shortcuts, rules, where to do things', domain: 'admin', icon: <FiLock />, view: 'documentation', who: 'anyone', keywords: 'help shortcuts rules guide manual' },
]

/** The home screen has no entry: it is the page the tiles are on. */
export const HOME = { label: 'Home', icon: <FiHome />, note: 'Your start page' }

/** The area a screen belongs to, by its view: hidden while this cooperative has it switched off (Settings > System > Features). */
const VIEW_FEATURE: Partial<Record<View, Feature>> = {
  loans: 'loans', loanapp: 'loans', loansetup: 'loans', amortization: 'loans', dormancy: 'savings', dividends: 'dividends',
  pos: 'pos', inventory: 'inventory', procurement: 'procurement', assets: 'assets', cic: 'cic',
}

export function featureOf(s: Pick<Screen, 'view' | 'tab'> | undefined): Feature | undefined {
  if (!s) return undefined
  if (s.view === 'accounting' && s.tab?.toLowerCase().startsWith('dividend')) return 'dividends'
  return VIEW_FEATURE[s.view]
}

/** True when this cooperative uses the screen's area. */
export function featureAllows(me: Me, s: Pick<Screen, 'view' | 'tab'> | undefined): boolean {
  return featureOn(me.featuresOff ?? [], featureOf(s))
}

/** The screens this person may open, in registry order: their role allows it and this cooperative uses its area. */
export function visibleScreens(me: Me): Screen[] {
  return SCREENS.filter((s) => can(me, s.who) && featureAllows(me, s))
}

/** The visible screens of each domain, domains in menu order, empty domains left out. */
export function byDomain(me: Me, only?: (s: Screen) => boolean): { id: DomainId; label: string; color: Tint; screens: Screen[] }[] {
  const shown = visibleScreens(me).filter((s) => !only || only(s))
  return DOMAIN_ORDER.map((id) => ({ id, ...DOMAINS[id], screens: shown.filter((s) => s.domain === id) })).filter((d) => d.screens.length > 0)
}

/** Which registry entry a place in the app belongs to, for highlighting the menu. A tab-specific entry wins over the screen's own. */
export function screenFor(view: View, tab?: string | null): Screen | undefined {
  return SCREENS.find((s) => s.view === view && s.tab === (tab ?? undefined)) ?? SCREENS.find((s) => s.view === view && !s.tab) ?? SCREENS.find((s) => s.view === view)
}
