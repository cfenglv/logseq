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
    signalChild(child, 'SIGTERM')
    if (await waitForExit(child, termTimeoutMs)) return
    signalChild(child, 'SIGKILL')
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
