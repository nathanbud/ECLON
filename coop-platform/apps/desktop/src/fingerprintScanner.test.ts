import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SimulatedScanner } from './fingerprintScanner'

const bits = (b64: string) => Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))
const differing = (a: Uint8Array, b: Uint8Array) => a.reduce((n, v, i) => n + (v ^ b[i]).toString(2).replace(/0/g, '').length, 0)

describe('simulated scanner', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    localStorage.clear()
  })

  const scan = async (member: string, finger: number) => {
    const p = SimulatedScanner.capture(member, finger)
    await vi.advanceTimersByTimeAsync(1500)
    return p
  }

  it('says that it is a simulator and produces SIM1 templates of 128 bytes', async () => {
    const r = await scan('1-100', 1)
    expect(SimulatedScanner.simulated).toBe(true)
    expect(r.format).toBe('SIM1')
    expect(bits(r.template)).toHaveLength(128)
    expect(r.quality).toBeGreaterThanOrEqual(25)
    expect(r.quality).toBeLessThanOrEqual(95)
  })

  it('re-scans the same finger close to before and other fingers or members far from it', async () => {
    const a = bits((await scan('1-100', 1)).template)
    const again = bits((await scan('1-100', 1)).template)
    const otherFinger = bits((await scan('1-100', 2)).template)
    const otherMember = bits((await scan('1-200', 1)).template)
    expect(differing(a, again)).toBeLessThan(120)          // the server matches at 80% of 1024 bits, so under 205 differing
    expect(differing(a, otherFinger)).toBeGreaterThan(300)
    expect(differing(a, otherMember)).toBeGreaterThan(300)
  })
})
