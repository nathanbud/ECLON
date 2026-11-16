import { renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { unsavedWork, useLeaveGuard } from './leaveGuard'

describe('leave guard', () => {
  it('reports unsaved work while a screen holds it and forgets it afterwards', () => {
    expect(unsavedWork()).toBeNull()
    const { rerender, unmount } = renderHook(({ m }: { m: string | null }) => useLeaveGuard(m), { initialProps: { m: 'Unsaved profile' as string | null } })
    expect(unsavedWork()).toBe('Unsaved profile')
    rerender({ m: null })
    expect(unsavedWork()).toBeNull()
    rerender({ m: 'Again' })
    expect(unsavedWork()).toBe('Again')
    unmount()
    expect(unsavedWork()).toBeNull()
  })
})
