import { describe, expect, it } from 'vitest'
import { CIC_WORDS, PRIVACY_REQUEST_WORDS, labelOf } from './labels'

describe('labelOf', () => {
  it('turns stored codes into words', () => {
    expect(labelOf('FOR_REVIEW')).toBe('Waiting for review')
    expect(labelOf('SOFT_CLOSED')).toBe('Closed for review')
    expect(labelOf('WAITING FOR APPROVAL')).toBe('Waiting for approval')
    expect(labelOf('FINAL')).toBe('Finished')
  })

  it('uses a screen’s own words where a code means something else there', () => {
    expect(labelOf('SUBMITTED')).toBe('Waiting for approval')
    expect(labelOf('SUBMITTED', CIC_WORDS)).toBe('Sent to the CIC')
    expect(labelOf('WITHDRAW_CONSENT', PRIVACY_REQUEST_WORDS)).toBe('Withdraw consent')
  })

  it('writes an unknown code in sentence case and an empty one as a dash', () => {
    expect(labelOf('TRANSFER_IN')).toBe('Transfer in')
    expect(labelOf('requester_name')).toBe('Requester name')
    expect(labelOf(null)).toBe('—')
    expect(labelOf('')).toBe('—')
  })
})
