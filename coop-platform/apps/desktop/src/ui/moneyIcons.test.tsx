import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { CoinIcon, PesoIcon } from './moneyIcons'

describe('money icons', () => {
  it('draw in the current colour, hidden from screen readers like the other icons', () => {
    for (const Icon of [PesoIcon, CoinIcon]) {
      const { container } = render(<Icon />)
      const svg = container.querySelector('svg')!
      expect(svg.getAttribute('stroke')).toBe('currentColor')
      expect(svg.getAttribute('aria-hidden')).toBe('true')
      expect(svg.getAttribute('width')).toBe('1em')
    }
  })

  it('the dollar sign is not used anywhere: money is in pesos', () => {
    const files = import.meta.glob(['../**/*.tsx', '../**/*.ts', '!../**/*.test.tsx', '!../**/*.test.ts'], { query: '?raw', import: 'default', eager: true })
    const using = Object.entries(files).filter(([, text]) => String(text).includes('FiDollarSign')).map(([name]) => name)
    expect(using).toEqual([])
  })
})
