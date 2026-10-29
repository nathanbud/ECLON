/** The version this build carries: Semantic Versioning, then the commit it was built from (a trailing + means uncommitted changes) and the build day. */
export const appVersion = {
  version: __APP_VERSION__,
  build: __APP_BUILD__,
  date: __APP_BUILD_DATE__,
}

/** For display: "Version 0.1.0 (build c0c16b3)". */
export const versionLabel = `Version ${appVersion.version} (build ${appVersion.build})`
