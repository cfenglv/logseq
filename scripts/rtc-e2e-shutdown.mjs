import { spawn } from 'node:child_process'

export const createWindowsProcessTreeSignaler = ({
  directFallbackTimeoutMs = 1_000,
  spawnProcess = spawn,
  timeoutMs = 5_000,
} = {}) => {
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
    throw new TypeError('taskkill timeout must be a positive number')
  }
  if (
    !Number.isFinite(directFallbackTimeoutMs) ||
    directFallbackTimeoutMs <= 0
  ) {
    throw new TypeError('direct-child fallback timeout must be a positive number')
  }

  const waitForDirectChildExit = (child) =>
    new Promise((resolve) => {
      if (child.exitCode !== null || child.signalCode !== null) {
        resolve(true)
        return
      }
      if (typeof child.once !== 'function') {
        resolve(false)
        return
      }
      const timeout = setTimeout(() => {
        child.removeListener?.('exit', onExit)
        resolve(false)
      }, directFallbackTimeoutMs)
      const onExit = () => {
        clearTimeout(timeout)
        resolve(true)
      }
      child.once('exit', onExit)
    })

  const stopDirectChildAfterHelperFailure = async (child, signal) => {
    const failures = []
    if (child.exitCode !== null || child.signalCode !== null) return failures
    const sendDirectSignal = (directSignal) => {
      try {
        child.kill(directSignal)
        return false
      } catch (error) {
        if (error?.code === 'ESRCH') return true
        failures.push(error)
        return false
      }
    }

    let gone = sendDirectSignal(signal)
    if (!gone) gone = await waitForDirectChildExit(child)
    if (!gone && signal === 'SIGTERM') {
      gone = sendDirectSignal('SIGKILL')
      if (!gone) gone = await waitForDirectChildExit(child)
    }
    if (!gone) {
      failures.push(
        new Error(
          `direct child process ${child.pid} survived Windows cleanup fallback`
        )
      )
    }
    return failures
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

    try {
      await new Promise((resolve, reject) => {
        let taskkill
        let timeout
        let settled = false
        const settle = (callback, value) => {
          if (settled) return
          settled = true
          clearTimeout(timeout)
          callback(value)
        }

        try {
          taskkill = spawnProcess('taskkill.exe', args, {
            shell: false,
            stdio: 'ignore',
            windowsHide: true,
          })
        } catch (error) {
          settle(reject, error)
          return
        }

        taskkill.once('error', (error) => settle(reject, error))
        taskkill.once('exit', (code, exitSignal) => {
          if (code === 0) {
            settle(resolve)
            return
          }
          settle(
            reject,
            new Error(
              `taskkill.exe failed for PID ${pid} during ${signal} ` +
                `(code=${code}, signal=${exitSignal})`
            )
          )
        })
        timeout = setTimeout(() => {
          const timeoutError = new Error(
            `taskkill.exe did not exit within ${timeoutMs}ms for PID ${pid} during ${signal}`
          )
          try {
            taskkill.kill('SIGKILL')
            settle(reject, timeoutError)
          } catch (killError) {
            settle(
              reject,
              new AggregateError(
                [timeoutError, killError],
                `failed to stop timed-out taskkill.exe for PID ${pid}`
              )
            )
          }
        }, timeoutMs)
      })
    } catch (helperError) {
      const fallbackFailures = await stopDirectChildAfterHelperFailure(
        child,
        signal
      )
      if (fallbackFailures.length === 0) throw helperError
      throw new AggregateError(
        [helperError, ...fallbackFailures],
        `Windows process-tree cleanup and direct-child fallback failed for PID ${pid}`
      )
    }
  }
}

export const createShutdownController = ({
  children,
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
