#!/usr/bin/env node

import path from 'node:path'
import { verifyMacosArchiveDesktopRuntimeRevision } from '../resources/desktop-runtime-provenance.mjs'

const parseArgs = (argv) => {
  const values = new Map()
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index]
    const value = argv[index + 1]
    if (!key?.startsWith('--') || !value || values.has(key)) {
      throw new Error(`invalid or duplicate argument near ${key || '<end>'}`)
    }
    values.set(key, value)
  }
  const required = (key) => {
    const value = values.get(key)
    if (!value) throw new Error(`missing ${key}`)
    return value
  }
  return Object.freeze({
    archive: path.resolve(required('--archive')),
    sourceRevision: required('--source-revision'),
  })
}

try {
  const args = parseArgs(process.argv.slice(2))
  const result = verifyMacosArchiveDesktopRuntimeRevision(args)
  console.log(
    `[desktop-archive-source-revision] OK sourceRevision=${result.sourceRevision} runtimes=${result.runtimePaths.length}`
  )
} catch (error) {
  console.error(
    `[desktop-archive-source-revision] RELEASE BLOCKED: ${
      error instanceof Error ? error.message : error
    }`
  )
  process.exitCode = 1
}
