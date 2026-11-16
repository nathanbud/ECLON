import { describe, expect, it } from 'vitest'
import type { Me } from '../api'
import { GROUPS } from '../profile/constants'
import { visibleScreens } from '../nav/screens'
import { normalize, parseCommand, similarity, type ScreenRef } from './commands'

const me = (roles: string[], admin = false) => ({ username: 'u', name: 'U', roles, canEdit: false, canApprove: false, admin, mustChangePassword: false }) as Me
const refs = (m: Me): ScreenRef[] => visibleScreens(m).map((s) => ({ id: s.id, label: s.label, view: s.view, tab: s.tab }))

const everything = refs(me(['ROLE_ADMIN', 'ROLE_SUPERADMIN', 'ROLE_ACCOUNTANT'], true))
const say = (text: string, inProfile = false, screens = everything) => parseCommand(text, { screens, inProfile })

describe('what is said, made plain', () => {
  it('lowers case, drops punctuation and keeps one space', () => {
    expect(normalize('  Open  the "Client-Profile", please! ')).toBe('open the client profile please')
    expect(normalize('Business & Employment')).toBe('business and employment')
  })

  it('scores an exact name 1, a word of the name high, and a near miss by spelling', () => {
    expect(similarity('journals', 'Journals')).toBe(1)
    expect(similarity('profile', 'Client Profile')).toBeGreaterThanOrEqual(0.88)
    expect(similarity('jurnals', 'Journals')).toBeGreaterThan(0.8)
    expect(similarity('banana', 'Journals')).toBeLessThan(0.5)
  })
})

describe('going home and the menu', () => {
  it.each(['go home', 'home', 'take me home', 'open the dashboard', 'main dashboard'])('"%s" goes home', (t) => expect(say(t)).toEqual({ kind: 'home' }))
  it.each(['menu', 'open menu', 'show the menu', 'close menu'])('"%s" toggles the menu', (t) => expect(say(t)).toEqual({ kind: 'menu' }))
})

describe('finding a client', () => {
  it('takes what follows as the name to search for', () => {
    expect(say('find client santos')).toEqual({ kind: 'find', text: 'santos' })
    expect(say('search for member maria cruz')).toEqual({ kind: 'find', text: 'maria cruz' })
    expect(say('look up budikey')).toEqual({ kind: 'find', text: 'budikey' })
    expect(say('find named ana reyes')).toEqual({ kind: 'find', text: 'ana reyes' })
    expect(say('find clients santos')).toEqual({ kind: 'find', text: 'santos' }) // the recognizer often hears the plural
    expect(say('search members maria')).toEqual({ kind: 'find', text: 'maria' })
  })

  it('with no name just opens the finder', () => {
    expect(say('find client')).toEqual({ kind: 'find', text: '' })
    expect(say('search')).toEqual({ kind: 'find', text: '' })
  })
})

describe('opening a screen', () => {
  it('opens a screen by its printed name, with or without a verb', () => {
    const r = say('open client profile')
    expect(r).toMatchObject({ kind: 'open', screen: { label: 'Client Profile', view: 'members' } })
    expect(say('go to journals')).toMatchObject({ kind: 'open', screen: { label: 'Journals' } })
    expect(say('journals')).toMatchObject({ kind: 'open', screen: { label: 'Journals' } })
  })

  it('understands everyday words for a screen', () => {
    expect(say('open members')).toMatchObject({ kind: 'open', screen: { label: 'Client Profile' } })
    expect(say('open teller')).toMatchObject({ kind: 'open', screen: { label: 'Front Office' } })
    expect(say('show audit')).toMatchObject({ kind: 'open', screen: { label: 'Audit Log' } })
  })

  it('forgives a slightly misheard name when asked to open something', () => {
    expect(say('open jurnals')).toMatchObject({ kind: 'open', screen: { label: 'Journals' } })
  })

  it('does not wander on a stray word that is not a screen', () => {
    expect(say('banana')).toMatchObject({ kind: 'unknown' })
    expect(say('open banana split')).toMatchObject({ kind: 'unknown' })
    expect(say('')).toEqual({ kind: 'none' })
    expect(say('please')).toEqual({ kind: 'none' })
  })

  it('only offers the screens this person may open', () => {
    const clerk = refs(me([]))
    expect(clerk.some((s) => s.label === 'Users')).toBe(false)
    expect(say('open users', false, clerk).kind).not.toBe('open')
    expect(say('open client profile', false, clerk)).toMatchObject({ kind: 'open' })
  })

  it('offers the choices when two screens fit equally well', () => {
    const two: ScreenRef[] = [{ id: 'a', label: 'Accounting periods', view: 'accounting' }, { id: 'b', label: 'Accounting memos', view: 'accounting' }]
    const r = say('open accounting', false, two)
    expect(r.kind).toBe('ambiguous')
    if (r.kind === 'ambiguous') expect(r.options.map((o) => o.label).sort()).toEqual(['Accounting memos', 'Accounting periods'])
  })
})

describe('inside a member’s profile', () => {
  it('names its sections and menu groups', () => {
    expect(say('savings', true)).toMatchObject({ kind: 'section', label: 'Savings' })
    expect(say('open share capital', true)).toMatchObject({ kind: 'section', label: 'Share Capital' })
    expect(say('go to loans', true)).toMatchObject({ kind: 'section', label: 'Loans' })
    expect(say('member info', true)).toEqual({ kind: 'group', group: 'Info', label: 'Member info' })
    expect(say('open accounts', true)).toEqual({ kind: 'group', group: 'Accounts', label: 'Accounts' })
    expect(say('other', true)).toEqual({ kind: 'group', group: 'Other', label: 'Other' })
  })

  it('leaves those names alone when no profile is open', () => {
    expect(say('share capital', false).kind).not.toBe('section')
    expect(say('open accounts', false).kind).not.toBe('group')
  })

  it('has the groups it listens for', () => {
    expect(GROUPS.map((g) => g.label)).toEqual(['Info', 'Accounts', 'Other'])
  })
})
