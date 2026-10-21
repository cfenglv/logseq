const DEFAULT_DEADLINE_MS = 1_500;
const DEFAULT_MAX_CHARS = 20_000;
const DEFAULT_MAX_ITEMS = 600;

class ControlledStop extends Error {
  constructor(reason) {
    super(reason);
    this.name = "ControlledStop";
    this.reason = reason;
  }
}

const now = () => globalThis.performance?.now?.() ?? Date.now();
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
// PDF.js is loaded separately from this Closure-compiled bundle. Keep its
// public field names (and the result fields read by ClojureScript) as strings.
const field = (value, name) => value == null ? undefined : Reflect.get(value, name);
const record = (entries) => Object.fromEntries(entries);

async function exactBytes(input) {
  if (input instanceof Uint8Array) {
    return new Uint8Array(input.buffer, input.byteOffset, input.byteLength);
  }
  if (input instanceof ArrayBuffer) return new Uint8Array(input);
  if (ArrayBuffer.isView(input)) {
    return new Uint8Array(input.buffer, input.byteOffset, input.byteLength);
  }
  if (input?.arrayBuffer) return new Uint8Array(await input.arrayBuffer());
  if (Array.isArray(input)) return new Uint8Array(input);
  throw new TypeError("Unsupported PDF byte source");
}

function metadataValue(metadata, key) {
  const value = field(field(metadata, "info"), key);
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

async function boundedCleanup(loadingTask, worker, forceWorkerDestroy) {
  if (!worker) return;
  if (!forceWorkerDestroy && loadingTask?.destroy) {
    try {
      await Promise.race([
        Promise.resolve(loadingTask.destroy()).catch(() => undefined),
        sleep(100),
      ]);
    } catch (_) {
      // Cleanup must not change the extraction result.
    }
  }
  try {
    worker.destroy();
  } catch (_) {
    // The worker may already have been destroyed by the deadline path.
  }
}

export async function extractFirstPageLiterature(input, options = {}) {
  const started = now();
  const deadlineMs = Math.max(1, field(options, "deadlineMs") ?? DEFAULT_DEADLINE_MS);
  const maxChars = Math.max(1, field(options, "maxChars") ?? DEFAULT_MAX_CHARS);
  const maxItems = Math.max(1, field(options, "maxItems") ?? DEFAULT_MAX_ITEMS);
  const signal = field(options, "signal");
  const pdfjs = globalThis.pdfjsLib;

  if (!field(pdfjs, "getDocument") || !field(pdfjs, "PDFWorker")) {
    return record([
      ["status", "unavailable"], ["reason", "pdfjs-unavailable"],
      ["pagesRequested", []], ["elapsedMs", now() - started],
    ]);
  }

  let worker;
  let workerPort;
  let loadingTask;
  let page;
  let reader;
  let stopReason = null;
  let rejectControl;
  const control = new Promise((_, reject) => { rejectControl = reject; });
  // A controlled rejection is always consumed by Promise.race below.
  control.catch(() => undefined);

  const stop = (reason) => {
    if (stopReason) return;
    stopReason = reason;
    workerPort?.terminate();
    worker?.destroy();
    rejectControl(new ControlledStop(reason));
  };
  const deadlineId = setTimeout(() => stop("deadline"), deadlineMs);
  const abort = () => stop("cancelled");
  signal?.addEventListener?.("abort", abort, { once: true });
  const bounded = (promise) => Promise.race([Promise.resolve(promise), control]);

  try {
    if (signal?.aborted) stop("cancelled");
    const data = await bounded(exactBytes(input));
    // A supplied port avoids PDF.js's global fake-worker fallback after a viewer
    // has opened. This task owns the port and terminates it on every exit.
    const name = `literature-${Math.random().toString(36).slice(2)}`;
    workerPort = new Worker(field(options, "workerSrc"), { type: "module", name });
    worker = new (field(pdfjs, "PDFWorker"))(record([["name", name], ["port", workerPort]]));
    loadingTask = field(pdfjs, "getDocument").call(pdfjs, record([
      ["data", data], ["worker", worker],
      ["useSystemFonts", true], ["disableFontFace", true],
      ["isEvalSupported", false], ["disableAutoFetch", true],
      ["disableStream", true],
      ...(typeof field(options, "cMapUrl") === "string" && field(options, "cMapUrl")
        ? [["cMapUrl", field(options, "cMapUrl")], ["cMapPacked", field(options, "cMapPacked") !== false]]
        : []),
    ]));
    const document = await bounded(loadingTask.promise);
    const metadata = await bounded(field(document, "getMetadata").call(document).catch(() => null));
    const title = metadataValue(metadata, "Title");
    const author = metadataValue(metadata, "Author");
    let chars = (title?.length ?? 0) + (author?.length ?? 0);
    if (chars > maxChars) throw new ControlledStop("character-limit");
    page = await bounded(field(document, "getPage").call(document, 1));
    const viewport = page.getViewport({ scale: 1 });
    reader = field(page, "streamTextContent").call(page).getReader();
    const items = [];
    let sourceItems = 0;
    let extractionStop = "page-end";

    while (true) {
      const chunk = await bounded(reader.read());
      if (chunk.done) break;
      for (const item of chunk.value.items) {
        sourceItems += 1;
        const text = field(item, "str") ?? "";
        const remaining = maxChars - chars;
        if (remaining <= 0 || items.length >= maxItems) {
          extractionStop = items.length >= maxItems ? "item-limit" : "character-limit";
          break;
        }
        const accepted = text.slice(0, remaining);
        if (accepted) {
          const transform = field(item, "transform");
          items.push(record([
            ["text", accepted],
            ["x", transform?.[4] ?? null],
            ["y", transform?.[5] ?? null],
            ["fontSize", Math.abs(transform?.[3] ?? field(item, "height") ?? 0)],
            ["width", field(item, "width") ?? null],
            ["hasEOL", Boolean(field(item, "hasEOL"))],
          ]));
          chars += accepted.length;
        }
        if (accepted.length < text.length || chars >= maxChars) {
          extractionStop = "character-limit";
          break;
        }
      }
      if (extractionStop !== "page-end") {
        // cancel() is also bounded: malformed streams must not keep the task alive.
        await bounded(reader.cancel(new ControlledStop(extractionStop)));
        break;
      }
    }

    return record([
      ["status", extractionStop === "page-end" ? "complete" : "incomplete"],
      ["reason", extractionStop], ["version", field(pdfjs, "version") ?? null],
      ["totalPages", field(document, "numPages")],
      ["pagesRequested", [1]], ["pageHeight", viewport.height],
      ["pageWidth", viewport.width],
      ["info", record([["title", title], ["author", author]])],
      ["items", items], ["chars", chars], ["sourceItems", sourceItems],
      ["elapsedMs", now() - started],
    ]);
  } catch (error) {
    if (error instanceof ControlledStop) {
      return record([
        ["status", error.reason === "cancelled" ? "cancelled" : "incomplete"],
        ["reason", error.reason], ["pagesRequested", page ? [1] : []],
        ["elapsedMs", now() - started],
      ]);
    }
    const passwordError = error?.name === "PasswordException";
    return record([
      ["status", "unreadable"],
      ["reason", passwordError ? "password" : "parse-error"],
      ["pagesRequested", page ? [1] : []],
      ["elapsedMs", now() - started],
    ]);
  } finally {
    clearTimeout(deadlineId);
    signal?.removeEventListener?.("abort", abort);
    try {
      page?.cleanup?.();
      await boundedCleanup(loadingTask, worker, Boolean(stopReason));
    } finally {
      workerPort?.terminate();
    }
  }
}

// Explicit pair comparison only. Rendering is sequential and reuses one canvas;
// neither page pixels nor extracted text survive this task.
export async function compareLiteraturePdfs(inputA, inputB, options = {}) {
  const started = now();
  const signal = field(options, "signal");
  const progress = field(options, "onProgress");
  const pdfjs = globalThis.pdfjsLib;
  const tasks = [];
  const canvas = document.createElement("canvas");
  let worker, port, renderTask, rejectControl;
  const control = new Promise((_, reject) => { rejectControl = reject; });
  control.catch(() => undefined);
  const bounded = promise => Promise.race([promise, control]);
  const abort = () => {
    renderTask?.cancel();
    port?.terminate();
    rejectControl(new ControlledStop("cancelled"));
  };
  const result = (status, reason, page = null) => record([
    ["status", status], ["reason", reason], ["page", page],
    ["elapsedMs", now() - started],
  ]);
  const call = (target, name, ...args) => field(target, name).apply(target, args);
  const equal = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const contentAnnotations = items => items.map(item =>
    Object.fromEntries(Object.entries(item).filter(([key]) => key !== "id")));
  const layout = content => field(content, "items").map(item =>
    record(["str", "dir", "width", "height", "transform", "hasEOL"]
      .map(key => [key, field(item, key)])));
  signal?.addEventListener("abort", abort, {once: true});

  try {
    if (signal?.aborted) throw new ControlledStop("cancelled");
    const inputs = [await exactBytes(inputA), await exactBytes(inputB)];
    const identities = [];
    for (const data of inputs) {
      const hash = await bounded(crypto.subtle.digest("SHA-256", data));
      identities.push(record([
        ["checksum", [...new Uint8Array(hash)].map(n => n.toString(16).padStart(2, "0")).join("")],
        ["size", data.byteLength],
      ]));
    }
    const name = "literature-pair";
    port = new Worker(field(options, "workerSrc"), {type: "module", name});
    worker = new (field(pdfjs, "PDFWorker"))(record([["name", name], ["port", port]]));
    for (const data of inputs) {
      tasks.push(call(pdfjs, "getDocument", record([
        ["data", data], ["worker", worker], ["useSystemFonts", true],
        ["isEvalSupported", false], ["disableAutoFetch", true], ["disableStream", true],
        ["cMapUrl", field(options, "cMapUrl")], ["cMapPacked", true],
        ["standardFontDataUrl", field(options, "standardFontDataUrl")],
      ])));
    }
    const [a, b] = await bounded(Promise.all(tasks.map(task => task.promise)));
    if (field(a, "numPages") !== field(b, "numPages")) return result("different", "page-count");
    for (const doc of [a, b]) {
      const forms = await bounded(call(doc, "getFieldObjects"));
      const layers = await bounded(call(doc, "getOptionalContentConfig"));
      if (forms || Object.keys(call(layers, "getGroups") ?? {}).length) {
        return result("unsupported", "forms-or-layers");
      }
    }
    const totalPages = field(a, "numPages");
    const context = canvas.getContext("2d", {willReadFrequently: true});
    const geometry = page => [field(page, "view"), field(page, "rotate"), field(page, "userUnit")];
    for (let number = 1; number <= totalPages; number++) {
      if (signal?.aborted) throw new ControlledStop("cancelled");
      progress?.(record([["page", number], ["totalPages", totalPages]]));
      const [pa, pb] = await bounded(Promise.all([call(a, "getPage", number), call(b, "getPage", number)]));
      try {
        if (!equal(geometry(pa), geometry(pb))) return result("different", "page-geometry", number);
        const [ta, tb, aa, ab] = await bounded(Promise.all([
          call(pa, "getTextContent"), call(pb, "getTextContent"),
          call(pa, "getAnnotations"), call(pb, "getAnnotations"),
        ]));
        if (!equal(layout(ta), layout(tb)) || !equal(contentAnnotations(aa), contentAnnotations(ab))) {
          return result("different", "page-content", number);
        }
        const viewport = call(pa, "getViewport", record([["scale", 1.5]]));
        canvas.width = Math.ceil(field(viewport, "width"));
        canvas.height = Math.ceil(field(viewport, "height"));
        const pixels = async page => {
          renderTask = call(page, "render", record([["canvasContext", context], ["viewport", viewport]]));
          Reflect.set(renderTask, "onContinue", continuation => requestAnimationFrame(continuation));
          await bounded(renderTask.promise);
          renderTask = null;
          return context.getImageData(0, 0, canvas.width, canvas.height).data;
        };
        const ap = await pixels(pa), bp = await pixels(pb);
        const ai = new Uint32Array(ap.buffer, ap.byteOffset, ap.byteLength / 4);
        const bi = new Uint32Array(bp.buffer, bp.byteOffset, bp.byteLength / 4);
        for (let index = 0; index < ai.length; index++) {
          if (ai[index] !== bi[index]) return result("different", "page-pixels", number);
        }
      } finally {
        call(pa, "cleanup"); call(pb, "cleanup");
      }
    }
    return record([...Object.entries(result("equal", null)), ["files", identities], ["totalPages", totalPages]]);
  } catch (error) {
    if (signal?.aborted || error instanceof ControlledStop) return result("cancelled", "cancelled");
    return result("unreadable", error?.name === "PasswordException" ? "password" : "parse-error");
  } finally {
    signal?.removeEventListener("abort", abort);
    canvas.width = 0; canvas.height = 0;
    try {
      await boundedCleanup({destroy: () => Promise.all(tasks.map(task => task.destroy()))}, worker, signal?.aborted);
    } finally {
      port?.terminate();
    }
  }
}
