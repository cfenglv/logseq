import { spawn } from 'node:child_process'

const throwCollectedErrors = (errors, message) => {
  if (errors.length === 1) throw errors[0]
  if (errors.length > 1) throw new AggregateError(errors, message)
}

export const releaseChildProcessHandles = (child) => {
  if (!child) return
  const failures = []
  for (const stream of [child.stdin, child.stdout, child.stderr]) {
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
    child.unref?.()
  } catch (error) {
    failures.push(error)
  }
  throwCollectedErrors(failures, 'failed to release child process handles')
}

export const createWindowsProcessTreeSignaler = ({
  directKillTimeoutMs = 1_000,
  directTermTimeoutMs = 1_000,
  helperKillTimeoutMs = 1_000,
  spawnProcess = spawn,
  timeoutMs = 5_000,
  totalTimeoutMs,
} = {}) => {
  const stageTimeouts = {
    directKillTimeoutMs,
    directTermTimeoutMs,
    helperKillTimeoutMs,
    timeoutMs,
  }
  for (const [name, value] of Object.entries(stageTimeouts)) {
    if (!Number.isFinite(value) || value <= 0) {
      throw new TypeError(`${name} must be a positive number`)
    }
  }
  const cleanupTimeoutMs =
    totalTimeoutMs ??
    timeoutMs + helperKillTimeoutMs + directTermTimeoutMs + directKillTimeoutMs
  if (!Number.isFinite(cleanupTimeoutMs) || cleanupTimeoutMs <= 0) {
    throw new TypeError('totalTimeoutMs must be a positive number')
  }

  const waitForProcessOutcome = (child, requestedTimeoutMs) =>
    new Promise((resolve) => {
      if (child.exitCode != null || child.signalCode != null) {
        resolve({
          code: child.exitCode,
          signal: child.signalCode,
          type: 'exit',
        })
        return
      }
      if (typeof child.once !== 'function') {
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
      timeout = setTimeout(
        () => settle({ type: 'timeout' }),
        Math.max(0, requestedTimeoutMs)
      )
    })

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
    const deadline = Date.now() + cleanupTimeoutMs
    const remaining = (stageTimeoutMs) =>
      Math.max(0, Math.min(stageTimeoutMs, deadline - Date.now()))

    const runTaskkill = async () => {
      let taskkill
      try {
        taskkill = spawnProcess('taskkill.exe', args, {
          shell: false,
          stdio: 'ignore',
          windowsHide: true,
        })
      } catch (error) {
        throw error
      }

      const outcome = await waitForProcessOutcome(
        taskkill,
        remaining(timeoutMs)
      )
      if (outcome.type === 'error') throw outcome.error
      if (outcome.type === 'exit') {
        if (outcome.code === 0) return
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
      try {
        const killAccepted = taskkill.kill('SIGKILL')
        if (killAccepted !== true) {
          failures.push(
            new Error(
              `taskkill.exe kill returned false for PID ${pid} during ${signal}`
            )
          )
        }
      } catch (error) {
        failures.push(error)
      }

      const killOutcome = await waitForProcessOutcome(
        taskkill,
        remaining(helperKillTimeoutMs)
      )
      if (killOutcome.type === 'error') failures.push(killOutcome.error)
      if (killOutcome.type !== 'exit') {
        failures.push(
          new Error(
            `taskkill.exe survived helper SIGKILL for PID ${pid} during ${signal}`
          )
        )
        try {
          releaseChildProcessHandles(taskkill)
        } catch (error) {
          failures.push(error)
        }
      }
      throwCollectedErrors(
        failures,
        `taskkill.exe cleanup failed for PID ${pid} during ${signal}`
      )
    }

    const stopDirectChild = async () => {
      if (child.exitCode !== null || child.signalCode !== null) return []
      const failures = []
      const runStage = async (directSignal, stageTimeoutMs) => {
        try {
          const killAccepted = child.kill(directSignal)
          if (killAccepted !== true) {
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
        const outcome = await waitForProcessOutcome(
          child,
          remaining(stageTimeoutMs)
        )
        if (outcome.type === 'error') failures.push(outcome.error)
        return outcome.type === 'exit'
      }

      let exited = await runStage(
        signal,
        signal === 'SIGTERM' ? directTermTimeoutMs : directKillTimeoutMs
      )
      if (!exited && signal === 'SIGTERM') {
        exited = await runStage('SIGKILL', directKillTimeoutMs)
      }
      if (!exited) {
        failures.push(
          new Error(`child process ${pid} survived direct SIGKILL fallback`)
        )
        try {
          releaseChildProcessHandles(child)
        } catch (error) {
          failures.push(error)
        }
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
  releaseChild = () => {},
  signalChild,
  waitForExit,
  termTimeoutMs = 5_000,
  killTimeoutMs = 5_000,
}) => {
  let cleanupPromise

  const stopChild = async (child) => {
    if (!child || child.exitCode !== null || child.signalCode !== null) return
    try {
      await signalChild(child, 'SIGTERM')
      if (await waitForExit(child, termTimeoutMs)) return
      await signalChild(child, 'SIGKILL')
      if (!(await waitForExit(child, killTimeoutMs))) {
        throw new Error(
          `child process ${child.pid ?? 'unknown'} survived SIGKILL`
        )
      }
    } catch (cleanupError) {
      try {
        releaseChild(child)
      } catch (releaseError) {
        throw new AggregateError(
          [cleanupError, releaseError],
          `cleanup and handle release failed for child ${child.pid ?? 'unknown'}`
        )
      }
      throw cleanupError
    }
  }

  const shutdown = () => {
    if (!cleanupPromise) {
      cleanupPromise = Promise.allSettled([...children].map(stopChild)).then(
        (results) => {
          const failures = results
            .filter((result) => result.status === 'rejected')
            .map((result) => result.reason)
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
