const ENDPOINT_PATH = "/logseq-literature/changes";
const MAX_DIRTY_KEYS = 512;
const LONG_POLL_MS = 25000;

let observerID = null;
let endpointClass = null;
let bridgeState = null;

function jsonResponse(status, body) {
  return [
    status,
    {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
    },
    JSON.stringify(body),
  ];
}

function parseAck(value) {
  if (value === null || value === "") return 0;
  if (!/^[0-9]+$/.test(value)) return null;
  return Number(value);
}

function createBridgeState(session) {
  return {
    session,
    sequence: 0,
    rescanSequence: 0,
    dirtyKeys: new Map(),
    waiters: new Set(),
    stopped: false,
  };
}

function responseBody(state, forceRescan = false) {
  const rescan = forceRescan || state.rescanSequence > 0;
  return {
    session: state.session,
    libraryKey: Zotero.Users.getLocalUserKey(),
    cursor: state.sequence,
    rescan,
    keys: rescan ? [] : [...state.dirtyKeys.keys()].sort(),
  };
}

function acknowledge(state, ack) {
  for (const [key, sequence] of state.dirtyKeys) {
    if (sequence <= ack) state.dirtyKeys.delete(key);
  }
  if (state.rescanSequence && state.rescanSequence <= ack) {
    state.rescanSequence = 0;
  }
}

function hasPendingChanges(state) {
  return state.rescanSequence > 0 || state.dirtyKeys.size > 0;
}

function finishWaiter(state, waiter, response) {
  if (!state.waiters.delete(waiter)) return;
  clearTimeout(waiter.timeoutID);
  waiter.resolve(response);
}

function flushWaiters(state) {
  if (!hasPendingChanges(state)) return;
  const response = jsonResponse(200, responseBody(state));
  for (const waiter of [...state.waiters]) {
    finishWaiter(state, waiter, response);
  }
}

function markRescan(state) {
  state.sequence += 1;
  state.rescanSequence = state.sequence;
  state.dirtyKeys.clear();
  flushWaiters(state);
}

function markKeys(state, keys) {
  const validKeys = [...new Set(keys)].filter((key) =>
    /^[A-Z0-9]{8}$/.test(key)
  );
  if (!validKeys.length) return;

  state.sequence += 1;
  for (const key of validKeys) {
    state.dirtyKeys.set(key, state.sequence);
  }
  if (state.dirtyKeys.size > MAX_DIRTY_KEYS) {
    state.rescanSequence = state.sequence;
    state.dirtyKeys.clear();
  }
  flushWaiters(state);
}

function itemKeysForIDs(ids) {
  const keys = [];
  for (const id of ids) {
    let item = Zotero.Items.get(id);
    if (!item) return null;
    if (item.parentItemID) {
      item = Zotero.Items.get(item.parentItemID);
      if (!item) return null;
    }
    if (item.key) keys.push(item.key);
  }
  return keys;
}

function notify(event, type, ids) {
  const state = bridgeState;
  if (!state || state.stopped) return;

  if (type === "item" && (event === "add" || event === "modify")) {
    const keys = itemKeysForIDs(ids);
    if (keys) markKeys(state, keys);
    else markRescan(state);
    return;
  }

  // Deletes no longer have a readable item key. Collection membership and
  // collection moves can change an authorized scope without changing an item.
  if (
    type === "item" ||
    type === "collection" ||
    type === "collection-item"
  ) {
    markRescan(state);
  }
}

async function waitForChanges(requestData) {
  const state = bridgeState;
  if (!state || state.stopped) {
    return jsonResponse(503, { reason: "not-running" });
  }

  if (requestData.searchParams.get("identity") === "1") {
    return jsonResponse(200, {libraryKey: Zotero.Users.getLocalUserKey()});
  }

  const requestedSession = requestData.searchParams.get("session");
  const ack = parseAck(requestData.searchParams.get("ack"));
  if (ack === null || (requestedSession === state.session && ack > state.sequence)) {
    return jsonResponse(400, { reason: "invalid-ack" });
  }

  if (requestedSession !== state.session) {
    return jsonResponse(200, responseBody(state, true));
  }

  acknowledge(state, ack);
  if (hasPendingChanges(state)) {
    return jsonResponse(200, responseBody(state));
  }

  return new Promise((resolve) => {
    const waiter = { resolve, timeoutID: null };
    waiter.timeoutID = setTimeout(() => {
      finishWaiter(
        state,
        waiter,
        jsonResponse(200, responseBody(state))
      );
    }, LONG_POLL_MS);
    state.waiters.add(waiter);
  });
}

async function startup() {
  await Zotero.initializationPromise;

  const session = Zotero.Utilities.randomString(24);
  bridgeState = createBridgeState(session);

  endpointClass = class LogseqLiteratureChangesEndpoint {
    supportedMethods = ["GET"];

    init(requestData) {
      return waitForChanges(requestData);
    }
  };
  Zotero.Server.Endpoints[ENDPOINT_PATH] = endpointClass;

  observerID = Zotero.Notifier.registerObserver(
    { notify },
    ["item", "collection", "collection-item"],
    "logseq-literature-notifier"
  );
}

function shutdown() {
  if (observerID !== null) {
    Zotero.Notifier.unregisterObserver(observerID);
    observerID = null;
  }

  if (Zotero.Server.Endpoints[ENDPOINT_PATH] === endpointClass) {
    delete Zotero.Server.Endpoints[ENDPOINT_PATH];
  }
  endpointClass = null;

  if (bridgeState) {
    bridgeState.stopped = true;
    for (const waiter of [...bridgeState.waiters]) {
      finishWaiter(
        bridgeState,
        waiter,
        jsonResponse(503, { reason: "stopped" })
      );
    }
    bridgeState.dirtyKeys.clear();
    bridgeState = null;
  }
}

function install() {}
function uninstall() {}
