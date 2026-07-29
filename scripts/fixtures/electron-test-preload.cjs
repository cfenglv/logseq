"use strict";

const { EventEmitter } = require("node:events");
const Module = require("node:module");
const os = require("node:os");
const path = require("node:path");
const vm = require("node:vm");

const originalRunInThisContext = vm.runInThisContext;
const mainContextDefaultLoader =
  vm.constants?.USE_MAIN_CONTEXT_DEFAULT_LOADER;
if (!mainContextDefaultLoader) {
  throw new Error(
    "Electron test preload requires vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER",
  );
}
vm.runInThisContext = function runInThisContextWithDynamicImport(
  code,
  options,
) {
  const normalizedOptions =
    typeof options === "string" ? { filename: options } : { ...options };
  return originalRunInThisContext.call(vm, code, {
    ...normalizedOptions,
    importModuleDynamically: mainContextDefaultLoader,
  });
};

Object.defineProperty(process, "resourcesPath", {
  configurable: true,
  value: path.resolve(__dirname, "..", "..", "resources"),
});

// Seed the test build's version seam before the compiled CLJS bundle loads so
// each subprocess exercises production policy with one fixed running version,
// without mutating a namespace export after module load.
const compiledTestVersion = process.env.LOGSEQ_TEST_COMPILED_VERSION;
if (compiledTestVersion) {
  globalThis.__LOGSEQ_TEST_COMPILED_VERSION__ = compiledTestVersion;
}

const noop = (() => {
  const target = function () {};
  const proxy = new Proxy(target, {
    apply() {
      return proxy;
    },
    construct() {
      return proxy;
    },
    get(_target, property) {
      if (property === "then") return undefined;
      return proxy;
    },
  });
  return proxy;
})();

const app = Object.assign(new EventEmitter(), {
  getName: () => "Logseq Test",
  getPath: (name) =>
    path.join(os.tmpdir(), "logseq-electron-test", String(name)),
  getVersion: () => compiledTestVersion ?? "2.0.1-selfhost.5",
  isReady: () => true,
  quit() {},
  whenReady: async () => {},
});
const ipcMain = Object.assign(new EventEmitter(), {
  handle() {},
  removeHandler() {},
});
const defaultSupportCalls = [];
const defaultIsUpdateSupported = (info) => {
  defaultSupportCalls.push(info);
  return info?.minimumSystemVersion !== "999.0.0";
};
const autoUpdater = Object.assign(new EventEmitter(), {
  allowDowngrade: false,
  allowPrerelease: false,
  autoDownload: false,
  autoInstallOnAppQuit: false,
  checkForUpdates: async () => ({
    isUpdateAvailable: false,
  }),
  defaultSupportCalls,
  downloadUpdate: async () => [],
  feedURLCalls: [],
  isUpdateSupported: defaultIsUpdateSupported,
  quitAndInstall() {},
  setFeedURL(options) {
    this.feedURLCalls.push(options);
    this.feedURL = options;
  },
  resetContractState() {
    this.allowDowngrade = false;
    this.allowPrerelease = false;
    this.autoDownload = false;
    this.autoInstallOnAppQuit = false;
    this.channel = undefined;
    this.defaultSupportCalls.length = 0;
    this.feedURLCalls.length = 0;
    this.feedURL = undefined;
    this.isUpdateSupported = defaultIsUpdateSupported;
  },
});
const electron = new Proxy(
  {
    app,
    BrowserWindow: noop,
    clipboard: noop,
    dialog: noop,
    ipcMain,
    Menu: noop,
    nativeImage: noop,
    powerMonitor: new EventEmitter(),
    protocol: noop,
    screen: noop,
    session: { defaultSession: noop },
    shell: noop,
  },
  {
    get(target, property) {
      return property in target ? target[property] : noop;
    },
  },
);

const originalLoad = Module._load;
Module._load = function loadWithElectronTestDoubles(
  request,
  parent,
  isMain,
) {
  if (request === "electron") return electron;
  if (request === "electron-updater") return { autoUpdater };
  return originalLoad.call(this, request, parent, isMain);
};

globalThis.__LOGSEQ_TEST_AUTO_UPDATER__ = autoUpdater;
