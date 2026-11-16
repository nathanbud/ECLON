import { describe, expect, it } from 'vitest'
import { FRESH_GROUPS, GROUPS, TABS, TAB_KEYS, groupForKey } from './constants'

describe('profile tabs', () => {
  it('keeps one key for every tab, in the same order', () => {
    expect(TAB_KEYS.length).toBe(TABS.length)
    expect(TAB_KEYS[TABS.indexOf('Beneficiaries')]).toBe('beneficiaries')
    expect(TAB_KEYS[TABS.indexOf('Spouse Info')]).toBe('spouse')
  })

  it('puts Beneficiaries right after Spouse Info under Info', () => {
    const info = GROUPS[0].tabs.map((i) => TABS[i])
    expect(info[info.indexOf('Spouse Info') + 1]).toBe('Beneficiaries')
  })

  it('leaves Beneficiaries out for a member who is not registered yet', () => {
    expect(FRESH_GROUPS[0].tabs.map((i) => TABS[i])).not.toContain('Beneficiaries')
  })
})

describe('keys for the menu groups', () => {
  const press = (key: string, mods: Partial<Record<'ctrlKey' | 'metaKey' | 'altKey' | 'shiftKey' | 'repeat', boolean>> = {}) =>
    groupForKey({ key, ctrlKey: false, metaKey: false, altKey: false, shiftKey: false, repeat: false, ...mods }, GROUPS)

  it('maps I, A and O to Member info, Accounts and Other, in either case', () => {
    expect(GROUPS[press('i')].label).toBe('Info')
    expect(GROUPS[press('A')].label).toBe('Accounts')
    expect(GROUPS[press('o')].label).toBe('Other')
  })

  it('ignores other keys and any press with a modifier or a held key', () => {
    expect(press('x')).toBe(-1)
    expect(press('a', { ctrlKey: true })).toBe(-1)
    expect(press('a', { metaKey: true })).toBe(-1)
    expect(press('i', { altKey: true })).toBe(-1)
    expect(press('o', { shiftKey: true })).toBe(-1)
    expect(press('a', { repeat: true })).toBe(-1)
  })

  it('has no Accounts key for a member who is not registered yet (that group is absent)', () => {
    expect(groupForKey({ key: 'a', ctrlKey: false, metaKey: false, altKey: false, shiftKey: false, repeat: false }, FRESH_GROUPS)).toBe(-1)
  })
})

