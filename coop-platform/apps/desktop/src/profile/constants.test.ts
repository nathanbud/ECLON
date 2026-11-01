import { describe, expect, it } from 'vitest'
import { FRESH_GROUPS, GROUPS, TABS, TAB_KEYS } from './constants'

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
