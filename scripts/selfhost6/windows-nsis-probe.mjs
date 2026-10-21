#!/usr/bin/env node
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { prepareWindowsArchiveReader } from "../lib/windows-archive-reader.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");

export function windowsProbeSources(root, templates, plugins) {
  const quote = value => '"' + value.replaceAll("$", () => "$$") + '"';
  const header = [
    "Unicode true", "RequestExecutionLevel user", "SilentInstall silent",
    "!addincludedir " + quote(path.join(templates, "include")),
    "!addplugindir /x86-unicode " + quote(path.join(plugins, "x86-unicode")),
    "!include LogicLib.nsh", "!include x64.nsh", "!include StdUtils.nsh",
  ];
  const inner = [
    ...header, "Name NativeProbeInner",
    "OutFile " + quote(path.join(root, "inner.exe")),
    '!define APP_GUID "example.invalid.windows-nsis-probe"',
    '!define APP_FILENAME "Native Probe"',
    '!define UNINSTALL_APP_KEY "example.invalid.windows-nsis-probe"',
    "!define INSTALL_MODE_PER_ALL_USERS_REQUIRED", "!define APP_64",
    "!include " + quote(path.join(templates, "multiUser.nsh")),
    "Var originalParameters", "Section",
    '${StdUtils.GetAllParameters} $originalParameters "0"',
    '${StdUtils.TestParameter} $R0 "allusers"',
    '${If} $R0 == "true"', "!insertmacro setInstallModePerAllUsers",
    "${Else}", "!insertmacro setInstallModePerUser", "${EndIf}",
    'SetOutPath "$INSTDIR"',
    "File /oname=TARGET_BUILD_MANIFEST.json " + quote(path.join(root, "TARGET_BUILD_MANIFEST.json")),
    "System::Call 'kernel32::CreateMutexW(p 0, i 0, w " + '"${APP_GUID}"' + ")p.r6?e'",
    "Pop $7", "${If} $6 == 0", "SetErrorLevel 54", "Quit", "${EndIf}",
    "${If} $7 == 183", "SetErrorLevel 53", "Quit", "${EndIf}",
    "System::Call 'kernel32::CloseHandle(p r6)'",
    'WriteINIStr "$INSTDIR\\probe.ini" "probe" "args" "$originalParameters"',
    'WriteINIStr "$INSTDIR\\probe.ini" "probe" "mode" "$installMode"',
    'WriteINIStr "$INSTDIR\\probe.ini" "probe" "child" "$EXEPATH"',
    "${If} ${UAC_IsAdmin}",
    'WriteINIStr "$INSTDIR\\probe.ini" "probe" "admin" "1"',
    "${Else}", 'WriteINIStr "$INSTDIR\\probe.ini" "probe" "admin" "0"', "${EndIf}",
    // Fixture delay makes an accidental Exec (without waiting) observable.
    "Sleep 300", 'WriteINIStr "$INSTDIR\\probe.ini" "probe" "complete" "yes"',
    '${StdUtils.TestParameter} $R0 "fixture-failure"',
    '${If} $R0 == "true"', "SetErrorLevel 23",
    "${Else}", "SetErrorLevel 0", "${EndIf}", "SectionEnd",
  ].join("\n") + "\n";
  const outer = [
    "!define BRIDGE_CONFIG " + quote(path.join(root, "bridge-config.nsh")),
    "!include " + quote(path.join(repoRoot, "bridge-source/resources/windows-project-updater/bridge.nsi")),
  ].join("\n") + "\n";
  return { inner, outer };
}

const nativeProbe = String.raw`
$ErrorActionPreference = "Stop"
$root = $env:WINDOWS_NSIS_PROBE_ROOT
function Require($condition, $message) {
  if (-not $condition) { throw $message }
}
function Read-Probe($file) {
  $data = @{}
  foreach ($line in Get-Content -LiteralPath $file) {
    if ($line -match "^(args|mode|child|admin|complete)=(.*)$") { $data[$matches[1]] = $matches[2] }
  }
  return $data
}
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
Require ($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) "probe-requires-runner-admin"
$user = "Nsis" + [Guid]::NewGuid().ToString("N").Substring(0,12)
$password = ConvertTo-SecureString ("Np!" + [Guid]::NewGuid().ToString("N")) -AsPlainText -Force
$account = $null
try {
  $account = New-LocalUser -Name $user -Password $password -AccountNeverExpires
  Add-LocalGroupMember -SID "S-1-5-32-545" -Member $user
  $credential = [PSCredential]::new(".\" + $user, $password)
  & icacls.exe $root /grant ("*" + $account.SID.Value + ":(OI)(CI)M") /T /Q | Out-Null
  Require ($LASTEXITCODE -eq 0) "probe-scratch-permission-failed"
  $cases = @(
    @{name="user path with spaces"; scope="--currentuser"; mode="CurrentUser"; admin="0"; code=0; ordinary=$true; extra=""},
    @{name="machine path with spaces"; scope="--allusers"; mode="all"; admin="1"; code=0; ordinary=$false; extra=""},
    @{name="failure path with spaces"; scope="--currentuser"; mode="CurrentUser"; admin="0"; code=23; ordinary=$true; extra=" --fixture-failure"}
  )
  foreach ($case in $cases) {
    $target = Join-Path $root $case.name
    New-Item -ItemType Directory -Path $target | Out-Null
    $seed = Join-Path $target "original.txt"
    [IO.File]::WriteAllText($seed, "original-value")
    $parameters = "--updated --force-run " + $case.scope + $case.extra + " /D=" + $target
    $options = @{FilePath=(Join-Path $root "outer.exe"); ArgumentList=$parameters; WorkingDirectory=$root; PassThru=$true; Wait=$true}
    if ($case.ordinary) { $options.Credential=$credential; $options.LoadUserProfile=$true }
    $process = Start-Process @options
    Write-Output ("case=" + $case.name + "; exit=" + $process.ExitCode)
    Require ($process.ExitCode -eq $case.code) "probe-child-exit-not-preserved"
    $observed = Read-Probe (Join-Path $target "probe.ini")
    Require ($observed.args -ceq ("/S " + $parameters)) "probe-parameters-changed"
    Require ($observed.mode -ceq $case.mode) "probe-scope-changed"
    Require ($observed.admin -ceq $case.admin) "probe-user-permission-mismatch"
    Require ($observed.complete -ceq "yes") "probe-did-not-wait"
    Require (-not (Test-Path -LiteralPath $observed.child)) "probe-child-temporary-file-retained"
    Require ([IO.File]::ReadAllText($seed) -ceq "original-value") "probe-seed-changed"
  }
  $rejectedTarget = Join-Path $root "corrupt payload must not install"
  $rejected = Start-Process -FilePath (Join-Path $root "bad-outer.exe") -ArgumentList ("--currentuser /D=" + $rejectedTarget) -WorkingDirectory $root -Credential $credential -LoadUserProfile -PassThru -Wait
  Require ($rejected.ExitCode -eq 57) "probe-corrupt-payload-not-rejected"
  Require (-not (Test-Path -LiteralPath $rejectedTarget)) "probe-corrupt-payload-executed"
  Write-Output "case=same-size corrupt payload; exit=57; child-not-executed"
} finally {
  if ($null -ne $account) { Remove-LocalUser -SID $account.SID }
}
`;

export function requireWindowsProbeRunner(env, platform, arch) {
  assert.equal(platform, "win32", "Windows probe requires Windows");
  assert.equal(env.GITHUB_ACTIONS, "true", "Windows probe requires an isolated Actions runner");
  assert.equal(env.RUNNER_OS, "Windows");
  assert.equal(env.WINDOWS_PROBE_ARCH, arch, "native runner architecture differs");
  assert.ok(["x64", "arm64"].includes(arch));
  assert.ok(env.RUNNER_TEMP);
}

async function main() {
  requireWindowsProbeRunner(process.env, process.platform, process.arch);
  const reportDirectory = path.join(process.env.RUNNER_TEMP, "windows-nsis-probe-report");
  fs.mkdirSync(reportDirectory);
  const root = fs.mkdtempSync(path.join(process.env.RUNNER_TEMP, "windows-nsis-probe-"));
  let stage = "tooling";
  let result;
  function run(command, args, label, env = process.env, timeout = 120_000, cwd = root) {
    const child = spawnSync(command, args, {
      cwd, env, encoding: "utf8", timeout, maxBuffer: 2 * 1024 * 1024,
    });
    fs.writeFileSync(path.join(reportDirectory, label + ".log"), (child.stdout ?? "") + (child.stderr ?? ""));
    if (child.error) throw child.error;
    assert.equal(child.status, 0, "native probe command failed: " + label + ", signal=" + child.signal);
    return child.stdout;
  }
  try {
    const requireStatic = createRequire(path.join(repoRoot, "static/package.json"));
    assert.equal(requireStatic("electron-builder/package.json").version, "26.17.0");
    const requireBuilder = createRequire(requireStatic.resolve("electron-builder"));
    const { getMakeNsisPath, getNsisPluginsPath } = requireBuilder("app-builder-lib/out/toolsets/windows.js");
    const { nsisTemplatesDir } = requireBuilder("app-builder-lib/out/targets/nsis/nsisUtil.js");
    // Match the unchanged F configuration: no custom NSIS toolset override.
    const compiler = await getMakeNsisPath(undefined);
    const plugins = await getNsisPluginsPath(undefined);
    const scripts = windowsProbeSources(root, nsisTemplatesDir, plugins);
    const manifest = '{"kind":"synthetic-native-probe","version":"0.0.0-fixture"}\n';
    fs.writeFileSync(path.join(root, "TARGET_BUILD_MANIFEST.json"), manifest);
    for (const role of ["inner", "outer"]) {
      stage = "compile-" + role;
      const file = path.join(root, role + ".nsi");
      fs.writeFileSync(file, scripts[role]);
      if (role === "outer") {
        const inner = fs.readFileSync(path.join(root, "inner.exe"));
        const quote = value => '"' + value.replaceAll("$", () => "$$") + '"';
        fs.writeFileSync(path.join(root, "bridge-config.nsh"), [
          "OutFile " + quote(path.join(root, "outer.exe")),
          "!addincludedir " + quote(path.join(nsisTemplatesDir, "include")),
          "!addplugindir /x86-unicode " + quote(path.join(plugins, "x86-unicode")),
          '!define BRIDGE_PAYLOAD_NAME "inner.exe"',
          "!define BRIDGE_PAYLOAD_SIZE " + inner.length,
          '!define BRIDGE_PAYLOAD_SHA512 "' + createHash("sha512").update(inner).digest("hex") + '"',
          'LangString BridgeErrorDescription 1033 "Synthetic update failed"',
          'LangString BridgeErrorDescription 2052 "Synthetic update failed"',
        ].join("\n") + "\n");
      }
      run(compiler.path, ["-V2", file], role, { ...process.env, ...compiler.env });
    }
    stage = "legacy-bridge-envelope";
    const readerPackage = requireStatic.resolve("7zip-bin/package.json");
    assert.equal(requireStatic("7zip-bin/package.json").version, "5.2.0");
    const reader = path.join(path.dirname(readerPackage), "win", process.arch, "7za.exe");
    const pe = fs.readFileSync(reader);
    const offset = pe.readUInt32LE(0x3c);
    assert.equal(pe.readUInt16LE(offset + 4), process.arch === "arm64" ? 0xaa64 : 0x8664);
    const bridgeManifest = '{"kind":"synthetic-bridge-probe","version":"0.0.0-bridge-fixture"}\n';
    fs.writeFileSync(path.join(root, "TARGET_BUILD_MANIFEST.json"), bridgeManifest);
    run(reader, ["a", "-tzip", "-mx=0", "-bd", "bridge.zip", "TARGET_BUILD_MANIFEST.json", "inner.exe"], "bridge-zip");
    fs.appendFileSync(path.join(root, "outer.exe"), fs.readFileSync(path.join(root, "bridge.zip")));
    const manifestArgs = file => ["e", "-bd", "-bb0", "-so", "-r", file, "TARGET_BUILD_MANIFEST.json"];
    assert.equal(run(reader, manifestArgs(path.join(root, "outer.exe")), "legacy-reader", process.env, 2000), bridgeManifest);
    run(reader, ["e", "-y", "-bd", "-o" + path.join(root, "bridge-extracted"), path.join(root, "outer.exe"), "inner.exe"], "bridge-payload");
    assert.deepEqual(fs.readFileSync(path.join(root, "bridge-extracted", "inner.exe")), fs.readFileSync(path.join(root, "inner.exe")));

    stage = "full-native-nsis-reader";
    const fullDirectory = path.join(root, "full-reader");
    await prepareWindowsArchiveReader({ arch: process.arch, outputDirectory: fullDirectory });
    assert.ok(fs.statSync(path.join(fullDirectory, "7zip-LICENSE.txt")).size > 0);
    assert.equal(run(path.join(fullDirectory, "7za.exe"), manifestArgs(path.join(root, "inner.exe")),
      "full-reader", process.env, 2000), manifest);
    if (process.arch === "x64") {
      const corrupt = path.join(root, "corrupt");
      fs.mkdirSync(corrupt);
      const changed = fs.readFileSync(path.join(root, "inner.exe"));
      changed[0] ^= 1;
      fs.writeFileSync(path.join(corrupt, "inner.exe"), changed);
      run(reader, ["a", "-tzip", "-mx=0", "-bd", path.join(root, "bad.zip"), "inner.exe"], "corrupt-zip",
        process.env, 120_000, corrupt);
      fs.copyFileSync(path.join(root, "outer.exe"), path.join(root, "bad-outer.exe"));
      // Keep the original stub only; replace the trailer, not the bound size/hash.
      const originalTrailerSize = fs.statSync(path.join(root, "bridge.zip")).size;
      fs.truncateSync(path.join(root, "bad-outer.exe"), fs.statSync(path.join(root, "outer.exe")).size - originalTrailerSize);
      fs.appendFileSync(path.join(root, "bad-outer.exe"), fs.readFileSync(path.join(root, "bad.zip")));
      stage = "native-contracts";
      const file = path.join(root, "probe.ps1");
      fs.writeFileSync(file, nativeProbe);
      run("pwsh.exe", ["-NoProfile", "-NonInteractive", "-File", file], "native",
        { ...process.env, WINDOWS_NSIS_PROBE_ROOT: root, TEMP: root, TMP: root });
    }
    result = { status: "pass", componentOnly: true, platform: process.platform, arch: process.arch,
      nativeReader: true, nsisArchiveExtraction: true,
      legacyBridgeEnvelope: "pass", bridgeCrcCheck: "force", fullNativeNsisReader: "pass",
      opaqueInnerUnchanged: true,
      standardUserAndHandoff: process.arch === "x64" ? "pass" : "not-run",
      productBridgeTemplate: true, fixedPayloadIntegrity: process.arch === "x64" ? "pass" : "not-run",
      uacInteraction: "not-verified", realClientUpgrade: "not-run" };
  } catch (error) {
    fs.writeFileSync(path.join(reportDirectory, "error.log"), String(error.stack ?? error) + "\n");
    result = { status: "fail", stage, componentOnly: true, arch: process.arch,
      uacInteraction: "not-verified", realClientUpgrade: "not-run" };
    process.exitCode = 1;
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
  fs.writeFileSync(path.join(reportDirectory, "result.json"), JSON.stringify(result, null, 2) + "\n");
  process.stdout.write(JSON.stringify(result) + "\n");
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url))
  main().catch(() => {
    process.stderr.write("windows-nsis-probe-rejected\n");
    process.exitCode = 1;
  });
