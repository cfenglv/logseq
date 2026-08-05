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
      cleanupPromise = Promise.all([...children].map(stopChild)).then(
        () => undefined
      )
    }
    return cleanupPromise
  }

  return Object.freeze({
    isShuttingDown: () => cleanupPromise !== undefined,
    shutdown,
  })
}
