import type { ReactNode } from 'react'
import {
  FiBarChart2, FiBook, FiBookOpen, FiBriefcase, FiClipboard, FiCreditCard, FiDollarSign, FiFilePlus, FiFileText, FiGitMerge, FiImage, FiInbox, FiList, FiPercent, FiRepeat,
  FiSend, FiSettings, FiShield, FiShoppingCart, FiSliders, FiTrendingUp, FiUserCheck, FiUserPlus, FiUsers, FiLock, FiCalendar, FiHome,
} from 'react-icons/fi'
import type { Me } from '../api'
import type { View } from '../Dashboard'
import type { Tint } from '../theme'

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
  | 'anyone' | 'admin' | 'superadmin' | 'accountant' | 'privacy' | 'documents' | 'purchasing' | 'inventory' | 'pos' | 'assets' | 'settings'

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
    case 'privacy': return me.admin || role(me, 'DATA_PROTECTION_OFFICER')
    case 'documents': return me.admin || anyRole(me, ['ACCOUNTANT', 'DOCUMENT_CLERK'])
    case 'purchasing': return me.admin || anyRole(me, ['PURCHASER', 'PURCHASE_APPROVER', 'RECEIVER', 'ACCOUNTANT'])
    case 'inventory': return me.admin || anyRole(me, ['STOREKEEPER', 'STOCK_APPROVER', 'RECEIVER', 'PURCHASER', 'PURCHASE_APPROVER', 'ACCOUNTANT'])
    case 'pos': return me.admin || anyRole(me, ['POS_CASHIER', 'POS_SUPERVISOR', 'STOREKEEPER', 'ACCOUNTANT'])
    case 'assets': return me.admin || anyRole(me, ['ASSET_CUSTODIAN', 'ACCOUNTANT'])
    case 'settings': return me.admin || anyRole(me, ['ACCOUNTANT', 'DOCUMENT_CLERK', 'DATA_PROTECTION_OFFICER'])
  }
}

export const SCREENS: Screen[] = [
  // my work
  { id: 'waiting', label: 'Waiting for you', note: 'Everything that needs your approval or action', domain: 'inbox', icon: <FiInbox />, view: 'approvals', who: 'anyone', tile: true, keywords: 'approvals inbox pending review requests tasks todo' },

  // members
  { id: 'members', label: 'Client Profile', note: 'Find a member, see and change their record', domain: 'members', icon: <FiUsers />, view: 'members', who: 'anyone', tile: true, keywords: 'members clients customers people profile search register beneficiaries' },

  // teller and cash
  { id: 'frontoffice', label: 'Front Office', note: 'Deposits, withdrawals, payments and the teller’s day', domain: 'teller', icon: <FiDollarSign />, view: 'frontoffice', who: 'anyone', tile: true, keywords: 'teller cash receipt disburse withdrawal deposit encashment cheque clearing drawer' },

  // loans
  { id: 'loanapp', label: 'Loan Application', note: 'Find a member and start a loan application', domain: 'loans', icon: <FiFilePlus />, view: 'loanapp', who: 'anyone', tile: true, keywords: 'loan apply new application borrow release' },

  // accounting
  { id: 'accounting', label: 'Accounting', note: 'Trial balance, statements, journals, memos and periods', domain: 'accounting', icon: <FiBookOpen />, view: 'accounting', who: 'accountant', tile: true, keywords: 'accounting ledger books general' },
  { id: 'acc-tb', label: 'Trial balance', note: 'Balances of every ledger account', domain: 'accounting', icon: <FiBarChart2 />, view: 'accounting', tab: 'Trial balance', who: 'accountant', keywords: 'tb debit credit' },
  { id: 'acc-statements', label: 'Financial statements', note: 'Financial condition and statement of operations', domain: 'accounting', icon: <FiFileText />, view: 'accounting', tab: 'Statements', who: 'accountant', keywords: 'balance sheet income operations condition' },
  { id: 'acc-journals', label: 'Journals', note: 'General journal entries and their approval', domain: 'accounting', icon: <FiClipboard />, view: 'accounting', tab: 'Journals', who: 'accountant', keywords: 'journal entry gj post approve' },
  { id: 'acc-memos', label: 'Debit/credit memos', note: 'Adjustments and transfers between members’ accounts', domain: 'accounting', icon: <FiRepeat />, view: 'accounting', tab: 'Memos', who: 'accountant', keywords: 'memo dcm debit credit transfer adjustment beneficiary' },
  { id: 'acc-periods', label: 'Accounting periods', note: 'Open and close the books', domain: 'accounting', icon: <FiCalendar />, view: 'accounting', tab: 'Periods', who: 'accountant', keywords: 'period close month year lock' },
  { id: 'acc-banks', label: 'Banks', note: 'Bank cash and reconciliation', domain: 'accounting', icon: <FiCreditCard />, view: 'accounting', tab: 'Banks', who: 'accountant', keywords: 'bank reconcile deposit withdrawal cash' },
  { id: 'acc-waivers', label: 'Penalty waivers', note: 'Loan payments where less penalty was charged', domain: 'accounting', icon: <FiPercent />, view: 'accounting', tab: 'Penalty waivers', who: 'accountant', keywords: 'penalty waiver loan' },
  { id: 'accounts', label: 'Chart of Accounts', note: 'Ledger accounts: add, rename, move and switch off', domain: 'accounting', icon: <FiBook />, view: 'accounts', who: 'anyone', tile: true, keywords: 'coa back office accounts ledger gl' },
  { id: 'assets', label: 'Fixed Assets', note: 'Asset register and depreciation', domain: 'accounting', icon: <FiTrendingUp />, view: 'assets', who: 'assets', tile: true, keywords: 'asset depreciation register disposal' },

  // purchasing and sales
  { id: 'procurement', label: 'Purchasing', note: 'Suppliers, requests, orders and receiving', domain: 'commerce', icon: <FiBriefcase />, view: 'procurement', who: 'purchasing', tile: true, keywords: 'purchase supplier order request receive payables' },
  { id: 'inventory', label: 'Inventory', note: 'Stock, movements, adjustments and counts', domain: 'commerce', icon: <FiList />, view: 'inventory', who: 'inventory', tile: true, keywords: 'stock warehouse products items count adjustment' },
  { id: 'pos', label: 'Point of Sale', note: 'Sell, take payment and handle returns', domain: 'commerce', icon: <FiShoppingCart />, view: 'pos', who: 'pos', tile: true, keywords: 'pos sale cashier shop store returns discount' },

  // reports
  { id: 'reports', label: 'Reports', note: 'Membership, loans, accounting, inventory and sales reports', domain: 'reports', icon: <FiBarChart2 />, view: 'reports', who: 'anyone', tile: true, keywords: 'report export csv print portfolio par delinquent' },

  // compliance and records
  { id: 'cic', label: 'CIC submission', note: 'Credit information file for the Credit Information Corporation', domain: 'compliance', icon: <FiSend />, view: 'cic', who: 'accountant', tile: true, keywords: 'credit information corporation bureau submit file' },
  { id: 'privacy', label: 'Data privacy', note: 'Requests, incidents, sharing, notices and retention', domain: 'compliance', icon: <FiShield />, view: 'privacy', who: 'privacy', tile: true, keywords: 'privacy dpo data protection incident consent' },
  { id: 'audit', label: 'Audit Log', note: 'Every change made, who made it and why', domain: 'compliance', icon: <FiClipboard />, view: 'audit', who: 'admin', tile: true, keywords: 'audit trail log history integrity seal' },
  { id: 'documents', label: 'Documents', note: 'Scanned IDs, contracts and deeds, and who may read them', domain: 'compliance', icon: <FiFileText />, view: 'documents', who: 'documents', tile: true, keywords: 'documents files scans records' },

  // administration
  { id: 'settings', label: 'Settings', note: 'Administrator controls in one place', domain: 'admin', icon: <FiSettings />, view: 'settings', who: 'settings', tile: true, keywords: 'settings preferences configuration administrator' },
  { id: 'users', label: 'Users', note: 'Temporary passwords, levels, roles and permissions', domain: 'admin', icon: <FiUserCheck />, view: 'users', who: 'admin', keywords: 'users accounts passwords sign in roles membership officer' },
  { id: 'loansetup', label: 'Loan Setup', note: 'Loan types, purposes, collateral and co-maker holds', domain: 'admin', icon: <FiSliders />, view: 'loansetup', who: 'admin', keywords: 'loan types purposes collateral setup' },
  { id: 'amortization', label: 'Amortization', note: 'How each schedule is worked out, with a calculator', domain: 'admin', icon: <FiPercent />, view: 'amortization', who: 'admin', keywords: 'amortization schedule calculator interest' },
  { id: 'workflows', label: 'Approval workflows', note: 'Extra approvals and the amounts they start at', domain: 'admin', icon: <FiGitMerge />, view: 'workflows', who: 'accountant', keywords: 'workflow approval limits steps' },
  { id: 'membershippolicy', label: 'Membership policy', note: 'Rules for returning members', domain: 'admin', icon: <FiUserPlus />, view: 'membershippolicy', who: 'admin', keywords: 'membership policy reactivation share capital' },
  { id: 'branding', label: 'Sign-in screen', note: 'The cooperative’s logo, name and tagline', domain: 'admin', icon: <FiImage />, view: 'branding', who: 'superadmin', keywords: 'logo brand name sign in' },
  { id: 'documentation', label: 'Documentation', note: 'Shortcuts, rules, where to do things', domain: 'admin', icon: <FiLock />, view: 'documentation', who: 'anyone', keywords: 'help shortcuts rules guide manual' },
]

/** The home screen has no entry: it is the page the tiles are on. */
export const HOME = { label: 'Home', icon: <FiHome />, note: 'Your start page' }

/** The screens this person may open, in registry order. */
export function visibleScreens(me: Me): Screen[] {
  return SCREENS.filter((s) => can(me, s.who))
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
