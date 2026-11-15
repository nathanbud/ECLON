import { describe, expect, it } from 'vitest'
import accountingSource from '../AccountingPage.tsx?raw'
import inventorySource from '../InventoryPage.tsx?raw'
import procurementSource from '../ProcurementPage.tsx?raw'
import type { Me } from '../api'
import { byDomain, can, DOMAIN_ORDER, DOMAINS, SCREENS, screenFor, visibleScreens } from './screens'

const me = (roles: string[] = [], admin = false): Me => ({ username: 'u', name: 'U', roles, canEdit: false, canApprove: false, admin, mustChangePassword: false })
const labels = (m: Me) => visibleScreens(m).map((s) => s.label)

describe('the screen registry', () => {
  it('names every screen once', () => {
    const ids = SCREENS.map((s) => s.id)
    expect(new Set(ids).size).toBe(ids.length)
    const places = SCREENS.map((s) => `${s.view}/${s.tab ?? ''}`)
    expect(new Set(places).size).toBe(places.length)
  })

  it('puts every screen in a known group', () => {
    for (const s of SCREENS) expect(DOMAIN_ORDER).toContain(s.domain)
    expect(Object.keys(DOMAINS).sort()).toEqual([...DOMAIN_ORDER].sort())
  })

  it('only names tabs the screen really has', () => {
    for (const s of SCREENS.filter((x) => x.view === 'accounting' && x.tab)) expect(accountingSource, s.tab).toContain(`'${s.tab}'`)
    expect(inventorySource).toContain("const TABS = ['Products', 'Movements', 'Adjustments', 'Counts', 'Setup']")
    expect(procurementSource).toContain("const TABS = ['Requests', 'Orders', 'Receipts', 'Suppliers']")
  })

  it('has a tile only for a screen of its own, and a note for every screen', () => {
    for (const s of SCREENS) expect(s.note.length, s.label).toBeGreaterThan(5)
    for (const s of SCREENS.filter((x) => x.tile)) expect(s.tab, `${s.label} is a tile, so it opens the whole screen`).toBeUndefined()
  })
})

describe('who sees what', () => {
  it('shows a person with no special role only the everyday screens', () => {
    const l = labels(me())
    expect(l).toEqual(expect.arrayContaining(['Waiting for you', 'Client Profile', 'Front Office', 'Loan Module', 'New loan application', 'Chart of Accounts', 'Reports', 'Documentation']))
    for (const hidden of ['Accounting', 'Debit/credit memos', 'Users', 'Audit Log', 'Purchasing', 'Inventory', 'Point of Sale', 'Fixed Assets', 'Data privacy', 'CIC submission', 'Settings']) expect(l).not.toContain(hidden)
  })

  it('shows an accountant the books, the memos and the credit file, but not the administrator controls', () => {
    const l = labels(me(['ROLE_ACCOUNTANT']))
    expect(l).toEqual(expect.arrayContaining(['Accounting', 'Journals', 'Debit/credit memos', 'Accounting periods', 'CIC submission', 'Fixed Assets', 'Settings', 'Approval workflows', 'Documents']))
    for (const hidden of ['Users', 'Audit Log', 'Loan Setup', 'Sign-in screen']) expect(l).not.toContain(hidden)
  })

  it('shows an administrator everything except what only a superadmin may open', () => {
    const l = labels(me([], true))
    expect(l).toEqual(expect.arrayContaining(['Users', 'Audit Log', 'Loan Setup', 'Accounting', 'Purchasing', 'Data privacy']))
    expect(l).not.toContain('Sign-in screen')
    expect(labels(me(['ROLE_SUPERADMIN']))).toContain('Sign-in screen')
  })

  it('matches each commerce screen to its roles', () => {
    expect(can(me(['ROLE_PURCHASER']), 'purchasing')).toBe(true)
    expect(can(me(['ROLE_PURCHASER']), 'pos')).toBe(false)
    expect(can(me(['ROLE_POS_CASHIER']), 'pos')).toBe(true)
    expect(can(me(['ROLE_STOREKEEPER']), 'inventory')).toBe(true)
    expect(can(me(['ROLE_ASSET_CUSTODIAN']), 'assets')).toBe(true)
    expect(can(me(['ROLE_DATA_PROTECTION_OFFICER']), 'privacy')).toBe(true)
    expect(can(me(['ROLE_DOCUMENT_CLERK']), 'documents')).toBe(true)
    expect(can(me(['ROLE_DOCUMENT_CLERK']), 'accountant')).toBe(false)
  })
})

describe('grouping and finding a screen', () => {
  it('lists the groups in menu order and leaves out the ones with nothing for this person', () => {
    const g = byDomain(me()).map((d) => d.id)
    expect(g).toEqual(['inbox', 'members', 'teller', 'loans', 'accounting', 'reports', 'admin'].filter((id) => g.includes(id as never)))
    expect(g).not.toContain('compliance')
    expect(byDomain(me(['ROLE_ACCOUNTANT'])).map((d) => d.id)).toContain('compliance')
  })

  it('can list only the tiles', () => {
    const tiles = byDomain(me([], true), (s) => !!s.tile).flatMap((d) => d.screens)
    expect(tiles.every((s) => s.tile)).toBe(true)
    expect(tiles.map((s) => s.label)).not.toContain('Journals')
  })

  it('finds the entry for a place in the app, preferring the tab-specific one', () => {
    expect(screenFor('accounting', 'Memos')?.label).toBe('Debit/credit memos')
    expect(screenFor('accounting')?.label).toBe('Accounting')
    expect(screenFor('accounting', 'Nonsense')?.label).toBe('Accounting')
    expect(screenFor('approvals')?.label).toBe('Waiting for you')
  })
})
