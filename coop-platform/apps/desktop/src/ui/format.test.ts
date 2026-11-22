import { describe, expect, it } from 'vitest'
import { nameCase } from './format'

describe('nameCase', () => {
  it('capitalises each word of a name kept in capitals', () => {
    expect(nameCase('JUAN DELA CRUZ')).toBe('Juan Dela Cruz')
    expect(nameCase('  maria luz  ')).toBe('Maria Luz')
  })

  it('capitalises after a hyphen, an apostrophe and an initial', () => {
    expect(nameCase("MARIA-LUZ O'NEIL")).toBe("Maria-Luz O'Neil")
    expect(nameCase('J.P. SANTOS')).toBe('J.P. Santos')
  })

  it('keeps roman numeral suffixes in capitals and letters with accents', () => {
    expect(nameCase('JOSE RIZAL III')).toBe('Jose Rizal III')
    expect(nameCase('ÑINO PEÑA')).toBe('Ñino Peña')
  })
})
