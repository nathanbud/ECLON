import { describe, expect, it } from 'vitest'
import type { UnitChoice } from '../api'
import { unitHeading, unitOptions } from './units'

const u = (id: number, name: string, parent: number | null, kind = 'OTHER', children = 0): UnitChoice =>
  ({ id, name, parent, kind, kindName: kind, kindChosen: true, disabled: false, children })

describe('units for the statements', () => {
  it('reads the department tree in order, each unit followed by those under it', () => {
    const o = unitOptions([u(3, 'BAUKO', 15), u(1, 'ALL', null, 'CONSOLIDATION', 1), u(15, 'MAIN CONSO', 1, 'CONSOLIDATION', 2), u(2, 'MAIN OFFICE', 15)])
    expect(o.map((x) => [x.unit.name, x.depth])).toEqual([['ALL', 0], ['MAIN CONSO', 1], ['MAIN OFFICE', 2], ['BAUKO', 2]])
  })

  it('keeps a unit whose parent is missing or that sits in a loop', () => {
    expect(unitOptions([u(5, 'ORPHAN', 99), u(6, 'A', 7), u(7, 'B', 6)]).map((x) => x.unit.id)).toEqual([5, 6, 7])
  })

  it('names the unit and, for a consolidation, how many units it adds up', () => {
    expect(unitHeading({ name: 'BAUKO SATELLITE OFFICE', kind: 'SATELLITE_OFFICE', kindName: 'Satellite office', units: 1 })).toBe('BAUKO SATELLITE OFFICE, satellite office')
    expect(unitHeading({ name: 'CONSOLIDATED MAIN', kind: 'CONSOLIDATION', kindName: 'Consolidation', units: 12 })).toBe('CONSOLIDATED MAIN, consolidation of 12 units')
  })
})
