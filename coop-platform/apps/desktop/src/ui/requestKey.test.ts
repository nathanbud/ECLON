import { renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { newRequestKey, useRequestKey } from './requestKey'

describe('request keys', () => {
  it('makes a key the server accepts, different each time', () => {
    const a = newRequestKey(), b = newRequestKey()
    expect(a).toMatch(/^[A-Za-z0-9-]{8,64}$/)
    expect(a).not.toBe(b)
  })

  it('keeps one key for the life of a dialog', () => {
    const { result, rerender } = renderHook(() => useRequestKey())
    const first = result.current
    rerender()
    expect(result.current).toBe(first)
  })
})
