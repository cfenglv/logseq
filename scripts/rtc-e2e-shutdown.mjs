import { spawn } from 'node:child_process'

const flattenCollectedErrors = (errors) =>
  errors.flatMap((error) =>
    error instanceof AggregateError ? flattenCollectedErrors(error.errors) : error
  )

const throwCollectedErrors = (errors, message) => {
  const flattened = flattenCollectedErrors(errors)
  if (flattened.length === 1) throw flattened[0]
  if (flattened.length > 1) throw new AggregateError(flattened, message)
}

const waitForProcessOutcome = (child, timeoutMs) =>
  new Promise((resolve) => {
    if (child?.exitCode != null || child?.signalCode != null) {
      resolve({ code: child.exitCode, signal: child.signalCode, type: 'exit' })
      return
    }
    if (typeof child?.once !== 'function') {
      resolve({ type: 'timeout' })
      return
    }
    let settled = false
    let timeout
    const settle = (outcome) => {
      if (settled) return
      settled = true
      clearTimeout(timeout)
      child.removeListener?.('error', onError)
      child.removeListener?.('exit', onExit)
      resolve(outcome)
    }
    const onError = (error) => settle({ error, type: 'error' })
    const onExit = (code, signal) => settle({ code, signal, type: 'exit' })
    child.once('error', onError)
    child.once('exit', onExit)
    timeout = setTimeout(() => settle({ type: 'timeout' }), timeoutMs)
  })

export const trackOwnedChild = (ownedChildren, child) => {
  if (!ownedChildren || !child) return child
  ownedChildren.add(child)
  child.once?.('close', () => ownedChildren.delete(child))
  return child
}

export const releaseOwnedChildHandles = (ownedChildren) => {
  const failures = []
  for (const child of [...(ownedChildren ?? [])]) {
    for (const stream of [child?.stdin, child?.stdout, child?.stderr]) {
      if (!stream) continue
      try {
        stream.unpipe?.()
      } catch (error) {
        failures.push(error)
      }
      try {
        stream.destroy?.()
      } catch (error) {
        failures.push(error)
      }
    }
    try {
      child?.unref?.()
    } catch (error) {
      failures.push(error)
    }
    ownedChildren?.delete(child)
  }
  throwCollectedErrors(failures, 'failed to release RTC child handles')
}

export const createWindowsProcessTreeSignaler = ({
  ownedChildren,
  spawnProcess = spawn,
  timeoutMs = 5_000,
} = {}) => {
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
    throw new TypeError('taskkill timeout must be a positive number')
  }

  return async (child, signal) => {
    if (!child || child.exitCode !== null || child.signalCode !== null) return
    if (!Number.isSafeInteger(child.pid) || child.pid <= 0) {
      throw new Error('Windows process-tree cleanup requires a valid positive integer PID')
    }
    if (signal !== 'SIGTERM' && signal !== 'SIGKILL') {
      throw new Error(`unsupported Windows cleanup signal: ${signal}`)
    }

    const pid = child.pid
    const args = ['/PID', String(pid), '/T']
    if (signal === 'SIGKILL') args.push('/F')

    const runTaskkill = async () => {
      let taskkill
      try {
        taskkill = trackOwnedChild(
          ownedChildren,
          spawnProcess('taskkill.exe', args, {
            shell: false,
            stdio: 'ignore',
            windowsHide: true,
          })
        )
      } catch (error) {
        throw error
      }

      const outcome = await waitForProcessOutcome(taskkill, timeoutMs)
      if (outcome.type === 'error') throw outcome.error
      if (outcome.type === 'exit') {
        if (
          outcome.code === 0 ||
          child.exitCode !== null ||
          child.signalCode !== null
        ) {
          return
        }
        throw new Error(
          `taskkill.exe failed for PID ${pid} during ${signal} ` +
            `(code=${outcome.code}, signal=${outcome.signal})`
        )
      }

      const failures = [
        new Error(
          `taskkill.exe did not exit within ${timeoutMs}ms for PID ${pid} during ${signal}`
        ),
      ]
      let helperKillAccepted = false
      try {
        helperKillAccepted = taskkill.kill('SIGKILL') === true
      } catch (error) {
        failures.push(error)
      }
      const killOutcome = helperKillAccepted
        ? await waitForProcessOutcome(taskkill, timeoutMs)
        : { type: 'timeout' }
      if (killOutcome.type === 'error') failures.push(killOutcome.error)
      if (helperKillAccepted && killOutcome.type !== 'exit') {
        failures.push(
          new Error(
            `taskkill.exe survived helper SIGKILL for PID ${pid} during ${signal}`
          )
        )
      }
      throwCollectedErrors(
        failures,
        `taskkill.exe cleanup failed for PID ${pid} during ${signal}`
      )
    }

    const stopDirectChild = async () => {
      if (child.exitCode !== null || child.signalCode !== null) return []
      if (typeof child.kill !== 'function') return []
      const failures = []
      const runStage = async (directSignal) => {
        let accepted = false
        try {
          accepted = child.kill(directSignal) === true
          if (!accepted) {
            failures.push(
              new Error(
                `direct child ${pid} kill returned false for ${directSignal}`
              )
            )
          }
        } catch (error) {
          if (error?.code === 'ESRCH') return true
          failures.push(error)
        }
        if (!accepted) return false
        const outcome = await waitForProcessOutcome(child, timeoutMs)
        if (outcome.type === 'error') failures.push(outcome.error)
        return outcome.type === 'exit'
      }

      let exited = await runStage(signal)
      if (!exited && signal === 'SIGTERM') exited = await runStage('SIGKILL')
      if (!exited) {
        failures.push(
          new Error(`child process ${pid} survived direct SIGKILL fallback`)
        )
      }
      return failures
    }

    try {
      await runTaskkill()
    } catch (helperError) {
      const directFailures = await stopDirectChild()
      throwCollectedErrors(
        [helperError, ...directFailures],
        `Windows cleanup failed for child process ${pid}`
      )
    }
  }
}

export const createShutdownController = ({
  children,
  releaseOwnedChildren = () => {},
  signalChild,
  waitForExit,
  termTimeoutMs = 5_000,
  killTimeoutMs = 5_000,
}) => {
  let cleanupPromise

  const stopChild = async (child) => {
    if (!child || child.exitCode !== null || child.signalCode !== null) return
    await signalChild(child, 'SIGTERM')
    if (await waitForExit(child, termTimeoutMs)) return
    await signalChild(child, 'SIGKILL')
    if (!(await waitForExit(child, killTimeoutMs))) {
      throw new Error(
        `child process ${child.pid ?? 'unknown'} survived SIGKILL`
      )
    }
  }

  const shutdown = () => {
    if (!cleanupPromise) {
      cleanupPromise = Promise.allSettled([...children].map(stopChild)).then(
        (results) => {
          const failures = results
            .filter((result) => result.status === 'rejected')
            .map((result) => result.reason)
          try {
            releaseOwnedChildren()
          } catch (error) {
            failures.push(error)
          }
          if (failures.length === 1) throw failures[0]
          if (failures.length > 1) {
            throw new AggregateError(failures, 'multiple RTC child cleanups failed')
          }
        }
      )
    }
    return cleanupPromise
  }

  return Object.freeze({
    isShuttingDown: () => cleanupPromise !== undefined,
    shutdown,
  })
}

export const reportRtcE2eErrors = ({
  primaryError,
  cleanupError,
  logError = console.error,
}) => {
  if (primaryError) logError(primaryError)
  if (cleanupError) logError('[rtc-e2e] cleanup failed:', cleanupError)
  return Boolean(primaryError || cleanupError)
}
