"use strict";

const { EventEmitter } = require("node:events");
const Module = require("node:module");
const os = require("node:os");
const path = require("node:path");

Object.defineProperty(process, "resourcesPath", {
  configurable: true,
  value:
    process.env.LOGSEQ_PROJECT_SIGNATURE_RESOURCES_PATH ??
    path.resolve(__dirname, "..", "..", "resources"),
});

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
  getName: () => "Logseq",
  getPath: (name) =>
    path.join(os.tmpdir(), "logseq-project-signature-contract", String(name)),
  getVersion: () => "2.0.1-selfhost.5",
  isPackaged: true,
  isReady: () => true,
  quit() {},
  whenReady: async () => {},
});
const ipcMain = Object.assign(new EventEmitter(), {
  handle() {},
  removeHandler() {},
});
const autoUpdater = Object.assign(new EventEmitter(), {
  allowDowngrade: false,
  allowPrerelease: false,
  autoDownload: false,
  autoInstallOnAppQuit: false,
  checkForUpdates: async () => ({ isUpdateAvailable: false }),
  downloadUpdate: async () => [],
  isUpdateSupported: () => true,
  quitAndInstall() {},
  setFeedURL() {},
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
Module._load = function loadWithElectronContractDoubles(
  request,
  parent,
  isMain,
) {
  if (request === "electron") return electron;
  if (request === "electron-updater") return { autoUpdater };
  if (request === "electron-log") return noop;
  if (request === "extract-zip") return noop;
  if (request === "socks-proxy-agent") return { SocksProxyAgent: noop };
  if (request === "socks") return { SocksClient: noop };
  return originalLoad.call(this, request, parent, isMain);
};
