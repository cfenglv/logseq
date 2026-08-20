import { spawnSync } from "node:child_process";

export function capture(repoRoot, command, args) {
  const result = spawnSync(command, args, {
    cwd: repoRoot,
    encoding: "utf8",
    shell: false,
    stdio: ["ignore", "pipe", "pipe"],
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(" ")} failed: ${result.stderr.trim() || result.status}`);
  }
  return result.stdout.trim();
}

export function assertClean(repoRoot, phase) {
  const status = capture(repoRoot, "git", ["status", "--porcelain", "--untracked-files=all"]);
  if (status) throw new Error(`${phase}: final gate requires a clean worktree`);
}

export function runSteps(repoRoot, gate, steps) {
  for (const step of steps) {
    const started = Date.now();
    console.log(`\n[${gate}] START ${step.label}`);
    const result = spawnSync(step.command, step.args, {
      cwd: step.cwd ?? repoRoot,
      env: { ...process.env, ...step.env },
      shell: false,
      stdio: "inherit",
      timeout: step.timeout,
    });
    if (result.error) throw result.error;
    if (result.signal) throw new Error(`${step.label} terminated by ${result.signal}`);
    if (result.status !== 0) throw new Error(`${step.label} failed with exit code ${result.status}`);
    console.log(`[${gate}] PASS ${step.label} (${((Date.now() - started) / 1000).toFixed(1)}s)`);
  }
}

export function printableSteps(repoRoot, steps) {
  return steps.map(({ label, command, args, cwd, env }) => ({
    label,
    command,
    args,
    ...(cwd ? { cwd: cwd.replace(`${repoRoot}/`, "") } : {}),
    ...(env ? { env: Object.keys(env).sort() } : {}),
  }));
}
