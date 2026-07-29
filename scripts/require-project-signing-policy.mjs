#!/usr/bin/env node

import fs from 'node:fs'
import { loadProjectSigningPolicy } from '../resources/project-updater-signature.mjs'

const versionSource = fs.readFileSync(
  new URL('../src/main/frontend/version.cljs', import.meta.url),
  'utf8'
)
const version = versionSource.match(/\(defonce version "([^"]+)"\)/)?.[1]

if (!version?.includes('-selfhost.')) {
  console.log('[project-signing-policy] NOT_APPLICABLE non-selfhost release')
  process.exit(0)
}

try {
  const policy = loadProjectSigningPolicy()
  console.log(
    `[project-signing-policy] READY keyId=${policy.keyId} minimumBootstrapRevision=${policy.minimumBootstrapRevision}`
  )
} catch (error) {
  console.error(
    `[project-signing-policy] RELEASE BLOCKED: ${
      error instanceof Error ? error.message : error
    }. Configure the project Ed25519 public key before building a distributable selfhost release.`
  )
  process.exitCode = 1
}
