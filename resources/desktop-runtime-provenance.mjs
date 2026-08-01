import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'

const sourceRevisionPattern = /^[0-9a-f]{40}$/

export const desktopRuntimePaths = Object.freeze([
  'electron.js',
  'db-worker-node.js',
  'logseq-cli.js',
  'js/main.js',
  'js/db-worker.js',
  'js/db-worker-node.js',
  'js/logseq-cli.js',
  'js/publishing/main.js',
])

export const assertDesktopSourceRevision = (sourceRevision) => {
  if (!sourceRevisionPattern.test(sourceRevision ?? '')) {
    throw new Error(
      'desktop source revision must be an exact lowercase 40-hex commit SHA'
    )
  }
  return sourceRevision
}

const readExactly = (fd, length, position, label) => {
  const payload = Buffer.alloc(length)
  let bytesRead = 0
  while (bytesRead < length) {
    const count = fs.readSync(
      fd,
      payload,
      bytesRead,
      length - bytesRead,
      position + bytesRead
    )
    if (count === 0) {
      throw new Error(`truncated ${label}`)
    }
    bytesRead += count
  }
  return payload
}

const asarEntry = (header, relativePath) => {
  let entry = header
  for (const segment of relativePath.split('/')) {
    entry = entry?.files?.[segment]
  }
  if (!entry) {
    throw new Error(`packaged app.asar is missing ${relativePath}`)
  }
  if (entry.unpacked) {
    throw new Error(
      `desktop runtime must remain inside app.asar: ${relativePath}`
    )
  }
  if (
    !Number.isSafeInteger(entry.size) ||
    entry.size < 0 ||
    !/^\d+$/.test(entry.offset ?? '')
  ) {
    throw new Error(`invalid app.asar entry metadata for ${relativePath}`)
  }
  return entry
}

export const verifyAsarDesktopRuntimeRevision = ({
  appAsar,
  sourceRevision,
}) => {
  const expected = assertDesktopSourceRevision(sourceRevision)
  const stats = fs.statSync(appAsar)
  if (!stats.isFile()) {
    throw new Error(`packaged app.asar is not a regular file: ${appAsar}`)
  }

  const fd = fs.openSync(appAsar, 'r')
  try {
    const prefix = readExactly(fd, 16, 0, 'app.asar header prefix')
    const outerHeaderSize = prefix.readUInt32LE(4)
    const jsonSize = prefix.readUInt32LE(12)
    if (
      outerHeaderSize < 8 ||
      jsonSize === 0 ||
      jsonSize > 64 * 1024 * 1024 ||
      jsonSize > outerHeaderSize
    ) {
      throw new Error('invalid app.asar header size')
    }
    const header = JSON.parse(
      readExactly(fd, jsonSize, 16, 'app.asar JSON header').toString('utf8')
    )
    const contentOffset = 8 + outerHeaderSize

    for (const relativePath of desktopRuntimePaths) {
      const entry = asarEntry(header, relativePath)
      const offset = contentOffset + Number(entry.offset)
      if (offset + entry.size > stats.size) {
        throw new Error(
          `app.asar entry exceeds archive bounds: ${relativePath}`
        )
      }
      const payload = readExactly(
        fd,
        entry.size,
        offset,
        `app.asar runtime ${relativePath}`
      )
      if (!payload.includes(expected)) {
        throw new Error(
          `packaged ${relativePath} does not contain source revision ${expected}`
        )
      }
    }
  } finally {
    fs.closeSync(fd)
  }

  return Object.freeze({
    runtimePaths: desktopRuntimePaths,
    sourceRevision: expected,
  })
}

const archiveEntries = (archive) => {
  const result = spawnSync('unzip', ['-Z1', archive], {
    encoding: 'utf8',
    shell: false,
    maxBuffer: 16 * 1024 * 1024,
  })
  if (result.error || result.status !== 0) {
    throw new Error(
      `could not list macOS archive ${archive}: ${
        result.stderr?.trim() ||
        result.error?.message ||
        `exit ${result.status}`
      }`
    )
  }
  return result.stdout.split(/\r?\n/).filter(Boolean)
}

const extractArchiveEntry = (archive, entry, destination) => {
  const output = fs.openSync(destination, 'wx', 0o600)
  try {
    const result = spawnSync('unzip', ['-p', archive, entry], {
      shell: false,
      stdio: ['ignore', output, 'pipe'],
      encoding: 'utf8',
      maxBuffer: 1024 * 1024,
    })
    if (result.error || result.status !== 0) {
      throw new Error(
        `could not extract ${entry}: ${
          result.stderr?.trim() ||
          result.error?.message ||
          `exit ${result.status}`
        }`
      )
    }
  } finally {
    fs.closeSync(output)
  }
}

export const verifyMacosArchiveDesktopRuntimeRevision = ({
  archive,
  sourceRevision,
}) => {
  const expected = assertDesktopSourceRevision(sourceRevision)
  const resolvedArchive = path.resolve(archive)
  const candidates = archiveEntries(resolvedArchive).filter((entry) =>
    /(?:^|\/)Logseq\.app\/Contents\/Resources\/app\.asar$/.test(entry)
  )
  if (candidates.length !== 1) {
    throw new Error(
      `expected exactly one Logseq app.asar in ${resolvedArchive}, found ${candidates.length}`
    )
  }

  const temporaryRoot = fs.mkdtempSync(
    path.join(os.tmpdir(), 'logseq-release-app-asar-')
  )
  const appAsar = path.join(temporaryRoot, 'app.asar')
  try {
    extractArchiveEntry(resolvedArchive, candidates[0], appAsar)
    return verifyAsarDesktopRuntimeRevision({
      appAsar,
      sourceRevision: expected,
    })
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
}
