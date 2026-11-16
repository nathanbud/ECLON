import { describe, expect, it } from 'vitest'
import { appVersion, versionLabel } from './version'

describe('build version', () => {
  it('follows Semantic Versioning: MAJOR.MINOR.PATCH, optionally with a pre-release', () => {
    expect(appVersion.version).toMatch(/^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$/)
  })

  it('names the commit the build came from and the day it was made', () => {
    expect(appVersion.build).toMatch(/^([0-9a-f]{7,40}\+?|unknown\+?)$/)
    expect(appVersion.date).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  })

  it('is written as "Version x.y.z (build abc1234)"', () => {
    expect(versionLabel).toBe(`Version ${appVersion.version} (build ${appVersion.build})`)
  })
})
