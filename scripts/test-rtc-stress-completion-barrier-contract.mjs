#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const stressPath = path.join(
  repoRoot,
  "clj-e2e",
  "test",
  "logseq",
  "e2e",
  "rtc_extra_part2_test.clj",
);
const runnerPath = path.join(repoRoot, "scripts", "run-rtc-e2e.mjs");
const prepushPath = path.join(repoRoot, "scripts", "run-rtc-prepush.mjs");
const rtcHelpersPath = path.join(
  repoRoot,
  "clj-e2e",
  "src",
  "logseq",
  "e2e",
  "rtc.clj",
);
const stressSource = fs.readFileSync(stressPath, "utf8");
const runnerSource = fs.readFileSync(runnerPath, "utf8");
const prepushSource = fs.readFileSync(prepushPath, "utf8");
const rtcHelpersSource = fs.readFileSync(rtcHelpersPath, "utf8");

const formAt = (source, start) => {
  let depth = 0;
  let inComment = false;
  let inString = false;
  let escaped = false;
  for (let index = start; index < source.length; index += 1) {
    const char = source[index];
    if (inComment) {
      if (char === "\n") inComment = false;
      continue;
    }
    if (inString) {
      if (escaped) escaped = false;
      else if (char === "\\") escaped = true;
      else if (char === '"') inString = false;
      continue;
    }
    if (char === ";") {
      inComment = true;
      continue;
    }
    if (char === '"') {
      inString = true;
      continue;
    }
    if (char === "(") depth += 1;
    if (char !== ")") continue;
    depth -= 1;
    if (depth === 0) return source.slice(start, index + 1);
  }
  throw new Error(`unterminated Clojure form at offset ${start}`);
};

const definitions = (source) => {
  const result = new Map();
  const pattern = /\((?:defn-?|deftest)\s+([^\s()[\]{}]+)/g;
  for (const match of source.matchAll(pattern)) {
    result.set(match[1], formAt(source, match.index));
  }
  for (const match of source.matchAll(/\(def\s/g)) {
    const form = formAt(source, match.index);
    const items = splitTopLevelItems(form);
    let nameIndex = 1;
    while (items[nameIndex]?.startsWith("^")) nameIndex += 1;
    if (bareSymbol.test(items[nameIndex] ?? "")) {
      result.set(items[nameIndex], form);
    }
  }
  return result;
};

const equalityForms = (source) =>
  [...source.matchAll(/\(=\s/g)].map((match) => formAt(source, match.index));

const calledSymbols = (source) =>
  new Set(
    [...source.matchAll(/\(([A-Za-z0-9*+!?<>=._/-]+)/g)].map(
      (match) => match[1],
    ),
  );

const calledSymbolsInOrder = (source) =>
  [...source.matchAll(/\(([A-Za-z0-9*+!?<>=._/-]+)/g)].map(
    (match) => ({ index: match.index, name: match[1] }),
  );

const splitTopLevelItems = (collection) => {
  const items = [];
  let start = null;
  let parenDepth = 0;
  let bracketDepth = 0;
  let braceDepth = 0;
  let inComment = false;
  let inString = false;
  let escaped = false;
  const flush = (end) => {
    if (start !== null) items.push(collection.slice(start, end));
    start = null;
  };
  for (let index = 1; index < collection.length - 1; index += 1) {
    const char = collection[index];
    if (inComment) {
      if (char === "\n") {
        inComment = false;
        if (parenDepth === 0 && bracketDepth === 0 && braceDepth === 0) {
          flush(index);
        }
      }
      continue;
    }
    if (inString) {
      if (escaped) escaped = false;
      else if (char === "\\") escaped = true;
      else if (char === '"') inString = false;
      continue;
    }
    const atTopLevel =
      parenDepth === 0 && bracketDepth === 0 && braceDepth === 0;
    if (char === ";") {
      if (atTopLevel) flush(index);
      inComment = true;
      continue;
    }
    if (atTopLevel && /[\s,]/.test(char)) {
      flush(index);
      continue;
    }
    if (start === null) start = index;
    if (char === '"') inString = true;
    else if (char === "(") parenDepth += 1;
    else if (char === ")") parenDepth -= 1;
    else if (char === "[") bracketDepth += 1;
    else if (char === "]") bracketDepth -= 1;
    else if (char === "{") braceDepth += 1;
    else if (char === "}") braceDepth -= 1;
  }
  flush(collection.length - 1);
  return items;
};

const callFormsInOrder = (source) =>
  calledSymbolsInOrder(source).map(({ index, name }) => ({
    name,
    items: splitTopLevelItems(formAt(source, index)),
  }));

const definitionParameters = (source) => {
  const items = splitTopLevelItems(source);
  const parameterVector = items.find((item) => item.startsWith("["));
  if (!parameterVector) return [];
  return splitTopLevelItems(parameterVector).filter((item) => item !== "&");
};

const bareSymbol = /^[A-Za-z*+!?<>=._/-][A-Za-z0-9*+!?<>=._/-]*$/;

const invokedHigherOrderDefinitions = (source, defs) => {
  const result = new Set();
  for (const { name, items } of callFormsInOrder(source)) {
    if (!defs.has(name)) continue;
    const parameters = definitionParameters(defs.get(name));
    const invokedParameters = calledSymbols(defs.get(name));
    const arguments_ = items.slice(1);
    for (let index = 0; index < arguments_.length; index += 1) {
      const argument = arguments_[index];
      const parameter = parameters[index];
      if (
        parameter &&
        invokedParameters.has(parameter) &&
        bareSymbol.test(argument) &&
        defs.has(argument)
      ) {
        result.add(argument);
      }
    }
  }
  return result;
};

const referencedConstantDefinitions = (source, defs) =>
  new Set(
    [...source.matchAll(/[A-Za-z*+!?<>=._/-][A-Za-z0-9*+!?<>=._/-]*/g)]
      .map((match) => match[0])
      .filter(
        (name) =>
          defs.has(name) && /^\(def\s/.test(defs.get(name)),
      ),
  );

const definitionClosure = (name, defs, seen = new Set()) => {
  if (seen.has(name) || !defs.has(name)) return "";
  seen.add(name);
  const source = defs.get(name);
  const referencedDefinitions = new Set([
    ...calledSymbols(source),
    ...invokedHigherOrderDefinitions(source, defs),
    ...referencedConstantDefinitions(source, defs),
  ]);
  return [
    source,
    ...[...referencedDefinitions].map((called) =>
      definitionClosure(called, defs, seen),
    ),
  ].join("\n");
};

const definitionClosureFromSource = (source, defs) => {
  const referencedDefinitions = new Set([
    ...calledSymbols(source),
    ...invokedHigherOrderDefinitions(source, defs),
    ...referencedConstantDefinitions(source, defs),
  ]);
  return [
    source,
    ...[...referencedDefinitions].map((name) =>
      definitionClosure(name, defs),
    ),
  ].join("\n");
};

const bindingValues = (source) => {
  const result = new Map();
  for (const { name, items } of callFormsInOrder(source)) {
    if (name === "def") {
      let nameIndex = 1;
      while (items[nameIndex]?.startsWith("^")) nameIndex += 1;
      if (bareSymbol.test(items[nameIndex] ?? "")) {
        result.set(items[nameIndex], items[nameIndex + 1]);
      }
      continue;
    }
    if (!["binding", "let", "loop"].includes(name)) continue;
    const bindings = items[1];
    if (!bindings?.startsWith("[")) continue;
    const bindingItems = splitTopLevelItems(bindings);
    for (let index = 0; index + 1 < bindingItems.length; index += 2) {
      if (bareSymbol.test(bindingItems[index])) {
        result.set(bindingItems[index], bindingItems[index + 1]);
      }
    }
  }
  return result;
};

const resolveBinding = (value, bindings, seen = new Set()) => {
  if (!bareSymbol.test(value) || !bindings.has(value) || seen.has(value)) {
    return value;
  }
  seen.add(value);
  return resolveBinding(bindings.get(value), bindings, seen);
};

const collectionItems = (value) => {
  if (value.startsWith("#{") && value.endsWith("}")) {
    return splitTopLevelItems(value.slice(1));
  }
  if (
    (value.startsWith("[") && value.endsWith("]")) ||
    (value.startsWith("(") && value.endsWith(")"))
  ) {
    const items = splitTopLevelItems(value);
    return items[0] === "vector" ? items.slice(1) : items;
  }
  return [];
};

const txAccessor = (value, bindings) => {
  const resolved = resolveBinding(value, bindings);
  if (!resolved.startsWith("(")) return null;
  const items = splitTopLevelItems(resolved);
  if (![":local-tx", ":remote-tx"].includes(items[0]) || items.length !== 2) {
    return null;
  }
  return {
    side: items[0],
    owner: resolveBinding(items[1], bindings),
  };
};

const hasFourClientTxApplyEquality = (source) => {
  const bindings = bindingValues(source);
  return callFormsInOrder(source).some(({ name, items }) => {
    if (name !== "apply" || items[1] !== "=" || items.length !== 3) {
      return false;
    }
    const values = collectionItems(resolveBinding(items[2], bindings));
    if (values.length !== 4) return false;
    const accesses = values.map((value) => txAccessor(value, bindings));
    if (accesses.some((access) => access === null)) return false;
    const sidesByOwner = new Map();
    for (const { owner, side } of accesses) {
      if (!sidesByOwner.has(owner)) sidesByOwner.set(owner, new Set());
      sidesByOwner.get(owner).add(side);
    }
    return (
      sidesByOwner.size === 2 &&
      [...sidesByOwner.values()].every(
        (sides) => sides.has(":local-tx") && sides.has(":remote-tx"),
      )
    );
  });
};

const positiveDuration = (value, bindings) => {
  const resolved = resolveBinding(value, bindings);
  return /^\d+$/.test(resolved) && Number(resolved) > 0;
};

const callSiteDurationOptions = (source, bindings) => {
  const maps = [];
  for (const { items } of callFormsInOrder(source)) {
    for (const item of items.slice(1)) {
      const resolved = resolveBinding(item, bindings);
      if (resolved.startsWith("{") && resolved.endsWith("}")) {
        maps.push(resolved);
      }
    }
  }
  for (const value of bindings.values()) {
    const resolved = resolveBinding(value, bindings);
    if (resolved.startsWith("{") && resolved.endsWith("}")) {
      maps.push(resolved);
    }
  }
  const relevant = maps
    .map((map) => splitTopLevelItems(map))
    .map((items) => {
      const options = new Map();
      for (let index = 0; index + 1 < items.length; index += 2) {
        options.set(items[index], items[index + 1]);
      }
      const stableKey = [...options.keys()].find((key) =>
        /:.*stable.*(?:ms|millis)/i.test(key),
      );
      const timeoutKey = [...options.keys()].find((key) =>
        /:.*timeout.*(?:ms|millis)/i.test(key),
      );
      return {
        present: Boolean(stableKey || timeoutKey),
        stable: stableKey ? resolveBinding(options.get(stableKey), bindings) : null,
        timeout: timeoutKey ? resolveBinding(options.get(timeoutKey), bindings) : null,
      };
    })
    .filter(({ present }) => present);
  return relevant.find(({ stable, timeout }) => stable && timeout) ??
    relevant[0] ??
    { present: false, stable: null, timeout: null };
};

const hasTimedStableWindow = (source) => {
  const bindings = bindingValues(source);
  const durationOptions = callSiteDurationOptions(source, bindings);
  const sourceDefinitions = definitions(source);
  const invokedFunctionParameters = new Set();
  for (const form of sourceDefinitions.values()) {
    const invoked = calledSymbols(form);
    for (const parameter of definitionParameters(form)) {
      if (invoked.has(parameter)) invokedFunctionParameters.add(parameter);
    }
  }
  const symbolPattern = (name) =>
    new RegExp(
      `(^|[^A-Za-z0-9*+!?<>=._/-])${name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}([^A-Za-z0-9*+!?<>=._/-]|$)`,
    );
  const references = (text, name) => symbolPattern(name).test(text);
  const conjunctiveEqualityForms = (expression) => {
    if (!expression?.startsWith("(")) return [];
    const items = splitTopLevelItems(expression);
    if (items[0] === "=" && items.length === 3) return [expression];
    if (items[0] !== "and") return [];
    return items.slice(1).flatMap(conjunctiveEqualityForms);
  };
  const conjunctiveSymbols = (expression) => {
    if (bareSymbol.test(expression)) return [expression];
    if (!expression?.startsWith("(")) return [];
    const items = splitTopLevelItems(expression);
    if (items[0] !== "and") return [];
    return items.slice(1).flatMap(conjunctiveSymbols);
  };
  const acceptedPredicatesFor = (sampled) =>
    [...bindings]
      .filter(([, value]) => {
        if (!value?.startsWith("(")) return false;
        const items = splitTopLevelItems(value);
        const directPredicate =
          items.length === 2 &&
          items[1] === sampled &&
          (invokedFunctionParameters.has(items[0]) ||
            sourceDefinitions.has(items[0]));
        if (directPredicate) return true;
        if (items[0] !== "true?" || items.length !== 2) return false;
        const inner = items[1];
        if (!inner.startsWith("(")) return false;
        const innerItems = splitTopLevelItems(inner);
        return (
          innerItems.length === 2 &&
          innerItems[1] === sampled &&
          (invokedFunctionParameters.has(innerItems[0]) ||
            sourceDefinitions.has(innerItems[0]))
        );
      })
      .map(([name]) => name);
  const stateEqualities = [];
  for (const [predicate, value] of bindings) {
    if (!value?.startsWith("(")) continue;
    const outerItems = splitTopLevelItems(value);
    const nestedConjunction = outerItems[0] === "and";
    for (const equality of conjunctiveEqualityForms(value)) {
      const items = splitTopLevelItems(equality);
      for (const [sampled, previous] of [
        [items[1], items[2]],
        [items[2], items[1]],
      ]) {
        const sampledValue = resolveBinding(sampled, bindings);
        if (!sampledValue.startsWith("(")) continue;
        const sampledCall = splitTopLevelItems(sampledValue);
        if (
          !bareSymbol.test(sampled) ||
          !bareSymbol.test(previous) ||
          sampledCall.length !== 1 ||
          (!invokedFunctionParameters.has(sampledCall[0]) &&
            !sourceDefinitions.has(sampledCall[0])) ||
          /(?:monotonic|nanoTime|performance[/.]now)/i.test(sampledCall[0])
        ) {
          continue;
        }
        const accepted = acceptedPredicatesFor(sampled).find((name) =>
          conjunctiveSymbols(value).includes(name),
        );
        if (nestedConjunction && !accepted) continue;
        stateEqualities.push({ predicate, sampled, previous, accepted });
      }
    }
  }
  const timeBindings = new Set(
    [...bindings]
      .filter(([, value]) =>
        /(?:monotonic|nanoTime|performance[/.]now)/i.test(value),
      )
      .map(([name]) => name),
  );
  const containsTime = (text) =>
    /(?:monotonic|nanoTime|performance[/.]now)/i.test(text) ||
    [...timeBindings].some((name) => references(text, name));
  const transitionEvidence = [];
  for (const state of stateEqualities) {
    const acceptedPredicates = state.accepted ?
      [state.accepted] :
      acceptedPredicatesFor(state.sampled);
    for (const [output, value] of bindings) {
      if (!value?.startsWith("(")) continue;
      const items = splitTopLevelItems(value);
      const accepted = acceptedPredicates.find((name) => references(value, name));
      if (!accepted || !references(value, state.predicate)) continue;
      let safeTransition = false;
      if (items[0] === "if" && items.length === 4) {
        const condition = items[1];
        const preserved = items[2];
        const reset = items[3];
        safeTransition =
          references(condition, accepted) &&
          references(condition, state.predicate) &&
          /stable[-\w]*since/i.test(preserved) &&
          containsTime(preserved) &&
          !/stable[-\w]*since/i.test(reset) &&
          (reset === "nil" || containsTime(reset));
      } else if (items[0] === "cond") {
        const pairs = [];
        for (let index = 1; index + 1 < items.length; index += 2) {
          pairs.push([items[index], items[index + 1]]);
        }
        const clearsRejected = pairs.some(
          ([condition, result]) =>
            /\bnot\b/.test(condition) &&
            references(condition, accepted) &&
            result === "nil",
        );
        const preservesSame = pairs.some(
          ([condition, result]) =>
            !/\bnot\b/.test(condition) &&
            references(condition, state.predicate) &&
            /stable[-\w]*since/i.test(result),
        );
        const restartsChanged = pairs.some(
          ([condition, result]) =>
            /\bnot\b/.test(condition) &&
            references(condition, state.predicate) &&
            containsTime(result),
        );
        const elsePair = pairs.find(([condition]) => condition === ":else");
        const elseRestarts =
          preservesSame &&
          elsePair &&
          !/stable[-\w]*since/i.test(elsePair[1]) &&
          containsTime(elsePair[1]);
        const elsePreserves =
          restartsChanged &&
          elsePair &&
          /stable[-\w]*since/i.test(elsePair[1]);
        const orderedExhaustiveTransition =
          pairs.length === 3 &&
          state.accepted === accepted &&
          pairs[0][0] === state.predicate &&
          /stable[-\w]*since/i.test(pairs[0][1]) &&
          pairs[1][0] === accepted &&
          !/stable[-\w]*since/i.test(pairs[1][1]) &&
          containsTime(pairs[1][1]) &&
          pairs[2][0] === ":else" &&
          pairs[2][1] === "nil";
        safeTransition = Boolean(
          (clearsRejected && (elseRestarts || elsePreserves)) ||
          orderedExhaustiveTransition,
        );
      }
      if (safeTransition) {
        transitionEvidence.push({ ...state, accepted, output });
      }
    }
  }
  const linkedElapsed = [];
  for (const transition of transitionEvidence) {
    for (const [elapsed, value] of bindings) {
      if (
        value?.includes("(-") &&
        references(value, transition.output) &&
        containsTime(value)
      ) {
        linkedElapsed.push({ ...transition, elapsed });
      }
    }
  }
  const linkedThresholds = [];
  for (const flow of linkedElapsed) {
    for (const { name, items } of callFormsInOrder(source)) {
      if (![">", ">="].includes(name) || items.length !== 3) continue;
      const [left, right] = items.slice(1);
      const duration = left === flow.elapsed ? right :
        right === flow.elapsed ? left : null;
      if (!duration) continue;
      const positive =
        positiveDuration(duration, bindings) ||
        (durationOptions.present &&
          /stable/i.test(duration) &&
          /^\d+$/.test(durationOptions.stable ?? "") &&
          Number(durationOptions.stable) > 0);
      if (positive) linkedThresholds.push({ ...flow, duration });
    }
  }
  const hasSuccessGuard = linkedThresholds.some((flow) =>
    callFormsInOrder(source).some(({ name, items }) => {
      const pairs = name === "if" && items.length === 4 ?
        [[items[1], items[2]]] :
        name === "cond" ?
          items.slice(1).reduce((result, item, index, all) => {
            if (index % 2 === 0 && index + 1 < all.length) {
              result.push([item, all[index + 1]]);
            }
            return result;
          }, []) :
          [];
      return pairs.some(([condition, result]) =>
        references(condition, flow.elapsed) &&
        references(result, flow.sampled) &&
        (references(condition, flow.output) ||
          (references(condition, flow.accepted) &&
            references(condition, flow.predicate))),
      );
    }),
  );
  const hasSafeDurationOptions =
    !durationOptions.present ||
    (/^\d+$/.test(durationOptions.stable ?? "") &&
      /^\d+$/.test(durationOptions.timeout ?? "") &&
      Number(durationOptions.stable) > 0 &&
      Number(durationOptions.timeout) > 0 &&
      Number(durationOptions.stable) <= Number(durationOptions.timeout));
  return Boolean(
    stateEqualities.length > 0 &&
    transitionEvidence.length > 0 &&
    linkedElapsed.length > 0 &&
    linkedThresholds.length > 0 &&
    hasSuccessGuard &&
    hasSafeDurationOptions,
  );
};

const barrierBehaviorViolations = (source) => {
  const violations = [];
  const equalities = equalityForms(source);
  if (!source.includes("@*page1") || !source.includes("@*page2")) {
    violations.push("completion barrier must sample both RTC clients");
  }
  if (
    !source.includes(":blocks") ||
    !source.includes(":rtc-tx")
  ) {
    violations.push("completion barrier must sample both page contents and tx state");
  }
  if (!source.includes("(loop") || !source.includes("(recur")) {
    violations.push("completion barrier must poll state instead of sleeping once");
  }
  if (
    !/(?:attempt|deadline|timeout)/i.test(source) ||
    !source.includes("(throw") ||
    !source.includes("ex-info")
  ) {
    violations.push("completion barrier timeout must be bounded and fail closed");
  }
  if (
    !/(?:wait-timeout|Thread\/sleep|setTimeout|w\/wait-for|\((?:[A-Za-z0-9*+!?<>=._/-]*(?:wait|sleep|pause|yield|pace|tick|cadence)[A-Za-z0-9*+!?<>=._/-]*)\b)/i.test(
      source,
    )
  ) {
    violations.push(
      "completion barrier polling must use a bounded non-busy cadence",
    );
  }
  const hasCausalTrigger =
    /(?:rtc\/)?with-wait-tx-updated/.test(source) ||
    (/rtc\/get-rtc-tx/.test(source) &&
      /rtc\/wait-(?:current-)?tx(?:-update-to|-synced)?/.test(source));
  if (!hasCausalTrigger) {
    violations.push("completion barrier must retain a real RTC causal trigger");
  }
  const hasPairwiseClientTxEquality =
    source.includes(":local-tx") &&
    source.includes(":remote-tx") &&
    equalities.filter(
      (form) => form.includes(":local-tx") && form.includes(":remote-tx"),
    ).length >= 2;
  if (
    !hasPairwiseClientTxEquality &&
    !hasFourClientTxApplyEquality(source)
  ) {
    violations.push("completion barrier must require local/remote tx convergence");
  }
  if (
    !/\(=\s+\(:blocks\s+[^)]+\)\s+\(:blocks\s+[^)]+\)\)/.test(
      source,
    )
  ) {
    violations.push("completion barrier must require equal final page contents");
  }
  const stabilityTerms =
    /(?:previous|prior|last|stable|unchanged|quiescent|consecutive)/i;
  const hasRepeatedStateEquality = equalities.some(
    (form) =>
      stabilityTerms.test(form) &&
      !form.includes(":local-tx") &&
      !form.includes(":remote-tx") &&
      !form.includes(":blocks"),
  );
  const hasStableThreshold =
    callFormsInOrder(source).some(({ name, items }) => {
      if (![">", ">=", "="].includes(name) || items.length !== 3) {
        return false;
      }
      const operands = items.slice(1);
      const expression = operands.join(" ");
      if (
        !stabilityTerms.test(expression) ||
        /(?:elapsed|duration|since|millis|[-_]ms\b|time)/i.test(expression)
      ) {
        return false;
      }
      return (
        operands.some((operand) => /^[2-9]\d*$/.test(operand)) ||
        operands.every((operand) => stabilityTerms.test(operand))
      );
    }) ||
    (/\(zero\?\s+[^\s)]*(?:stable|unchanged|quiescent|consecutive)[^\s)]*\)/i.test(
      source,
    ) &&
      /[^\s\[]*(?:stable|unchanged|quiescent|consecutive)[^\s\]]*\s+[2-9]\d*/i.test(
        source,
      ));
  if (
    !(hasRepeatedStateEquality && hasStableThreshold) &&
    !hasTimedStableWindow(source)
  ) {
    violations.push(
      "completion barrier must require at least two identical consecutive observations",
    );
  }
  return violations;
};

const completionContractViolations = (
  source,
  runner = runnerSource,
  prepush = prepushSource,
  rtcHelpers = rtcHelpersSource,
) => {
  const violations = [];
  const defs = definitions(source);
  for (const [name, form] of definitions(rtcHelpers)) {
    if (!defs.has(name)) defs.set(name, form);
    defs.set(`rtc/${name}`, form);
  }
  const stress = defs.get("online-two-clients-undo-redo-stress-test") ?? "";
  const assertionIndex = stress.indexOf("(assert-two-pages-synced!");
  const assertionCall =
    assertionIndex === -1 ? "" : formAt(stress, assertionIndex);
  const assertionHeadEnd = assertionCall.indexOf(
    "assert-two-pages-synced!",
  ) + "assert-two-pages-synced!".length;
  const assertionArguments = assertionCall
    .slice(assertionHeadEnd, -1)
    .trim();
  const lastParallelIndex = stress.lastIndexOf(
    "(run-two-clients-in-parallel!",
    assertionIndex,
  );
  const synchronizationStart =
    lastParallelIndex === -1
      ? 0
      : lastParallelIndex + formAt(stress, lastParallelIndex).length;
  const synchronizationPhase =
    assertionIndex === -1
      ? ""
      : stress.slice(synchronizationStart, assertionIndex);
  const barrierEvaluationSurface = [
    synchronizationPhase,
    assertionArguments,
  ].join("\n");
  const assertionArgumentCandidates = [
    ...new Set(
      calledSymbolsInOrder(assertionArguments)
        .map(({ name }) => name)
        .filter(
          (name) => name !== "assert-two-pages-synced!" && defs.has(name),
        ),
    ),
  ];
  const barrierCandidates = [
    ...new Set(
      calledSymbolsInOrder(barrierEvaluationSurface)
        .map(({ name }) => name)
        .filter(
          (name) => name !== "assert-two-pages-synced!" && defs.has(name),
        ),
    ),
  ];
  const combinedBarrierSource = definitionClosureFromSource(
    barrierEvaluationSurface,
    defs,
  );
  const barrierAnalyses = [
    {
      name:
        barrierCandidates.length > 0
          ? barrierCandidates.join(" -> ")
          : "post-operation synchronization phase",
      source: combinedBarrierSource,
    },
    ...barrierCandidates.map((name) => ({
      name,
      source: `${barrierEvaluationSurface}\n${definitionClosure(name, defs)}`,
    })),
  ];
  const safeBarrier = barrierAnalyses.find(
    ({ source: barrierSource }) =>
      barrierBehaviorViolations(barrierSource).length === 0,
  );
  if (!safeBarrier) {
    const closest = barrierAnalyses
      .map(({ name, source: barrierSource }) => ({
        name,
        violations: barrierBehaviorViolations(barrierSource),
      }))
      .sort((left, right) => left.violations.length - right.violations.length)[0];
    violations.push(
      `stress test needs a bounded two-client stable completion barrier before assertions${
        closest
          ? `; closest ${closest.name}: ${closest.violations.join("; ")}`
          : ""
      }`,
    );
  }

  const contentAssertion = defs.get("assert-two-pages-synced!") ?? "";
  const assertionTxEqualities = equalityForms(contentAssertion).filter(
    (form) => form.includes(":local-tx") && form.includes(":remote-tx"),
  );
  if (
    !stress.includes("(assert-two-pages-synced!") ||
    !/\(is\s+\(=\s+\(:blocks\s+[^)]+\)\s+\(:blocks\s+[^)]+\)\)/.test(
      contentAssertion,
    ) ||
    assertionTxEqualities.length < 2
  ) {
    violations.push("stress test must retain original content and both-client tx assertions");
  }
  if (
    !stress.includes("(assert-no-severe-sync-errors!") ||
    !source.includes("db-sync/checksum-mismatch") ||
    !source.includes("db-sync/tx-rejected") ||
    !source.includes("db-sync/apply-remote-txs-failed")
  ) {
    violations.push("stress test must retain severe RTC console-log assertions");
  }
  if (
    !runner.includes('"rtc-extra-part2-test"') ||
    !(runner.match(/await\s+runChild\(/g) ?? []).length ||
    !prepush.includes(
      '["scripts/run-rtc-e2e.mjs", "rtc-extra-part2-test"]',
    )
  ) {
    violations.push("contract must continue through the real RTC E2E path");
  }
  if (/(?:retry|rerun)/i.test(stress)) {
    violations.push("stress test must not retry its whole scenario");
  }
  const runnerChildCalls = runner.match(/await\s+runChild\(/g) ?? [];
  const part2Gate = prepush.slice(
    prepush.indexOf("RTC browser E2E part 2"),
    prepush.indexOf("const finalSha"),
  );
  if (runnerChildCalls.length !== 1 || /(?:retry|rerun)/i.test(part2Gate)) {
    violations.push("runner and prepush gate must not retry a failed RTC shard");
  }
  const assertionArgumentClosure = definitionClosureFromSource(
    assertionArguments,
    defs,
  );
  if (
    /\(catch\s+(?:Throwable|Exception)\b/.test(barrierEvaluationSurface) ||
    (assertionArgumentCandidates.length > 0 &&
      /\(catch\s+(?:Throwable|Exception)[\s\S]{0,180}\b(?:nil|false|true)\b/.test(
        assertionArgumentClosure,
      )) ||
    /\(try[\s\S]{0,1200}\(assert-two-pages-synced![\s\S]{0,500}\(catch\s+(?:Throwable|Exception)/.test(
      stress,
    )
  ) {
    violations.push("stress test must not swallow barrier or assertion failures");
  }
  return violations;
};

const safeFixture = `
(def severe-sync-log-patterns
  ["db-sync/checksum-mismatch"
   "db-sync/tx-rejected"
   "db-sync/apply-remote-txs-failed"])
(defn- page-sync-state [page]
  {:rtc-tx (rtc/get-rtc-tx) :blocks (get-blocks page)})
(defn- sync-by-trigger! []
  (rtc/with-wait-tx-updated
    (new-block-safe! "completion-fence")))
(defn- wait-for-two-client-fixpoint! []
  (loop [attempts 20 previous nil stable-samples 0]
    (when (zero? attempts)
      (throw (ex-info "completion barrier timeout" {:attempts 20})))
    (let [page1-state (w/with-page @*page1 (page-sync-state @*page1))
          page2-state (w/with-page @*page2 (page-sync-state @*page2))
          blocks-equal? (= (:blocks page1-state) (:blocks page2-state))
          tx-converged? (and (= (:local-tx (:rtc-tx page1-state)) (:remote-tx (:rtc-tx page1-state)))
                             (= (:local-tx (:rtc-tx page2-state)) (:remote-tx (:rtc-tx page2-state))))
          current [page1-state page2-state]
          unchanged? (= previous current)
          stable-samples' (if (and blocks-equal? tx-converged? unchanged?)
                            (inc stable-samples)
                            0)]
      (if (>= stable-samples' 2)
        current
        (do (Thread/sleep 100)
            (recur (dec attempts) current stable-samples'))))))
(defn- assert-two-pages-synced! []
  (let [s1 (page-sync-state @*page1) s2 (page-sync-state @*page2)
        tx1 (:rtc-tx s1) tx2 (:rtc-tx s2)]
    (is (= (:blocks s1) (:blocks s2)))
    (is (= (:local-tx tx1) (:remote-tx tx1)))
    (is (= (:local-tx tx2) (:remote-tx tx2)))))
(defn- assert-no-severe-sync-errors! [] severe-sync-log-patterns)
(deftest online-two-clients-undo-redo-stress-test
  (run-two-clients-in-parallel!)
  (sync-by-trigger!)
  (wait-for-two-client-fixpoint!)
  (assert-two-pages-synced!)
  (assert-no-severe-sync-errors!))
`;

const safeRunner = `
const supportedTasks = new Set(["rtc-extra-part2-test"]);
await runChild("bb", [testTask]);
`;
const safePrepush = `
run("RTC browser E2E part 2", process.execPath,
  ["scripts/run-rtc-e2e.mjs", "rtc-extra-part2-test"]);
const finalSha = capture("git", ["rev-parse", "HEAD"]);
`;
const localBarrierForm = definitions(safeFixture).get(
  "wait-for-two-client-fixpoint!",
);
const safeExternalHelperFixture = safeFixture
  .replace(localBarrierForm, "")
  .replaceAll("sync-by-trigger!", "establish-causal-watermark!")
  .replace(
    "(wait-for-two-client-fixpoint!)",
    "(rtc/wait-for-two-client-fixpoint! page-sync-state #(Thread/sleep 100) @*page1 @*page2)",
  );
const safeExternalRtcHelpers = `
(defn wait-for-two-client-fixpoint! [sample cadence page1 page2]
  (loop [attempts 20 previous nil stable-samples 0]
    (when (zero? attempts)
      (throw (ex-info "completion barrier timeout" {:attempts 20})))
    (let [page1-state (sample page1)
          page2-state (sample page2)
          blocks-equal? (= (:blocks page1-state) (:blocks page2-state))
          tx-converged? (and (= (:local-tx (:rtc-tx page1-state)) (:remote-tx (:rtc-tx page1-state)))
                             (= (:local-tx (:rtc-tx page2-state)) (:remote-tx (:rtc-tx page2-state))))
          current [page1-state page2-state]
          unchanged? (= previous current)
          stable-samples' (if (and blocks-equal? tx-converged? unchanged?)
                            (inc stable-samples)
                            0)]
      (if (>= stable-samples' 2)
        current
        (do (cadence)
            (recur (dec attempts) current stable-samples'))))))
`;
const renamedFixture = safeFixture
  .replaceAll("sync-by-trigger!", "establish-causal-watermark!")
  .replaceAll(
    "wait-for-two-client-fixpoint!",
    "observe-two-client-fixpoint!",
  );
const safeRenamedIndirectFixture = renamedFixture
  .replace(
    "  (establish-causal-watermark!)\n  (observe-two-client-fixpoint!)\n  (assert-two-pages-synced!)",
    "  (settle-after-concurrent-ops!)\n  (assert-two-pages-synced!)",
  )
  .replace(
    "(deftest online-two-clients-undo-redo-stress-test",
    `(defn- settle-after-concurrent-ops! []
  (establish-causal-watermark!)
  (observe-two-client-fixpoint!))
(deftest online-two-clients-undo-redo-stress-test`,
  );
const safeAssertionArgumentFixture = safeRenamedIndirectFixture
  .replace(
    "(defn- assert-two-pages-synced! []",
    "(defn- assert-two-pages-synced! [_settled-state]",
  )
  .replace(
    "  (settle-after-concurrent-ops!)\n  (assert-two-pages-synced!)",
    "  (assert-two-pages-synced! (settle-after-concurrent-ops!))",
  );
const safeHigherOrderFixture = `
(def severe-sync-log-patterns
  ["db-sync/checksum-mismatch"
   "db-sync/tx-rejected"
   "db-sync/apply-remote-txs-failed"])
(defn- page-sync-state [page]
  {:rtc-tx (rtc/get-rtc-tx) :blocks (get-blocks page)})
(defn- sample-two-pages []
  [(w/with-page @*page1 (page-sync-state @*page1))
   (w/with-page @*page2 (page-sync-state @*page2))])
(defn- four-tx-and-blocks-converged? [[page1-state page2-state]]
  (and (= (:blocks page1-state) (:blocks page2-state))
       (= (:local-tx (:rtc-tx page1-state)) (:remote-tx (:rtc-tx page1-state)))
       (= (:local-tx (:rtc-tx page2-state)) (:remote-tx (:rtc-tx page2-state)))))
(defn- sync-by-trigger! []
  (rtc/with-wait-tx-updated
    (new-block-safe! "completion-fence")))
(defn- assert-two-pages-synced! [_settled-state]
  (let [s1 (page-sync-state @*page1) s2 (page-sync-state @*page2)
        tx1 (:rtc-tx s1) tx2 (:rtc-tx s2)]
    (is (= (:blocks s1) (:blocks s2)))
    (is (= (:local-tx tx1) (:remote-tx tx1)))
    (is (= (:local-tx tx2) (:remote-tx tx2)))))
(defn- assert-no-severe-sync-errors! [] severe-sync-log-patterns)
(deftest online-two-clients-undo-redo-stress-test
  (run-two-clients-in-parallel!)
  (sync-by-trigger!)
  (assert-two-pages-synced!
    (rtc/wait-for-stable-state!
      sample-two-pages
      four-tx-and-blocks-converged?
      #(Thread/sleep 100)))
  (assert-no-severe-sync-errors!))
`;
const safeHigherOrderRtcHelpers = `
(defn wait-for-stable-state! [sample acceptable? cadence]
  (loop [attempts 20 previous nil stable-samples 0]
    (when (zero? attempts)
      (throw (ex-info "completion barrier timeout" {:attempts 20})))
    (let [current (sample)
          acceptable-state? (acceptable? current)
          unchanged? (= previous current)
          stable-samples' (if (and acceptable-state? unchanged?)
                            (inc stable-samples)
                            0)]
      (if (>= stable-samples' 2)
        current
        (do (cadence)
            (recur (dec attempts) current stable-samples'))))))
`;
const higherOrderPredicateForm = definitions(safeHigherOrderFixture).get(
  "four-tx-and-blocks-converged?",
);
const safeTimedWindowFixture = safeHigherOrderFixture
  .replace(
    higherOrderPredicateForm,
    `(defn- four-tx-and-blocks-converged? [[page1-state page2-state]]
  (let [tx-values [(:local-tx (:rtc-tx page1-state))
                   (:remote-tx (:rtc-tx page1-state))
                   (:local-tx (:rtc-tx page2-state))
                   (:remote-tx (:rtc-tx page2-state))]]
    (and (= (:blocks page1-state) (:blocks page2-state))
         (apply = tx-values))))`,
  )
  .replace(
    "rtc/wait-for-stable-state!",
    "rtc/wait-for-stable-window!",
  );
const safeTimedWindowRtcHelpers = `
(defn wait-for-stable-window! [sample acceptable? cadence]
  (let [stable-ms 300]
    (loop [attempts 100
           previous-state nil
           stable-since nil]
      (if (zero? attempts)
        (throw (ex-info "completion barrier timeout" {:attempts 100}))
        (let [current-state (sample)
              acceptable-state? (acceptable? current-state)
              same-state? (= previous-state current-state)
              now (util/monotonic-time-ms)
              next-stable-since (if (and acceptable-state? same-state?)
                                  (or stable-since now)
                                  (when acceptable-state? now))
              stable-elapsed (if next-stable-since
                               (- now next-stable-since)
                               0)]
          (if (and acceptable-state?
                   same-state?
                   (>= stable-elapsed stable-ms))
            current-state
            (do (cadence)
                (recur (dec attempts)
                       current-state
                       next-stable-since))))))))
`;
const safeOptionTimedWindowFixture = safeTimedWindowFixture
  .replace(
    "(defn- sync-by-trigger! []",
    `(defn- completion-window-options []
  (let [stable-window-ms 300
        timeout-window-ms 5000]
    {:stable-ms stable-window-ms
     :timeout-ms timeout-window-ms}))
(defn- sync-by-trigger! []`,
  )
  .replace(
    "      #(Thread/sleep 100)))",
    "      #(Thread/sleep 100)\n      (completion-window-options)))",
  );
const safeOptionTimedWindowRtcHelpers = `
(defn wait-for-stable-window! [sample acceptable? cadence options]
  (let [stable-ms (or (:stable-ms options) 0)
        timeout-ms (or (:timeout-ms options) 0)]
    (loop [previous-state nil
           stable-since nil
           started-at (util/monotonic-time-ms)]
      (let [current-state (sample)
            acceptable-state? (acceptable? current-state)
            same-state? (= previous-state current-state)
            sampled-at (util/monotonic-time-ms)
            next-stable-since (cond
                                (not acceptable-state?) nil
                                same-state? (or stable-since sampled-at)
                                :else sampled-at)
            stable-elapsed (if next-stable-since
                             (- sampled-at next-stable-since)
                             0)]
        (if (>= (- sampled-at started-at) timeout-ms)
          (throw (ex-info "completion barrier timeout" {:timeout-ms timeout-ms}))
          (if (and acceptable-state?
                   same-state?
                   (>= stable-elapsed stable-ms))
            current-state
            (do (cadence)
                (recur current-state next-stable-since started-at))))))))
`;
const safeArbitraryStateRtcHelpers = safeOptionTimedWindowRtcHelpers
  .replaceAll("previous-state", "prior-view")
  .replaceAll("current-state", "fresh-view")
  .replaceAll("acceptable-state?", "quiet-enough?")
  .replaceAll("same-state?", "matches-prior?");
const safeStagedGuardRtcHelpers = safeArbitraryStateRtcHelpers.replace(
  `(if (and quiet-enough?
                   matches-prior?
                   (>= stable-elapsed stable-ms))
            fresh-view
            (do`,
  `(cond
            (and next-stable-since
                 (>= stable-elapsed stable-ms))
            fresh-view
            :else
            (do`,
);
const safeNestedAndStateRtcHelpers = safeStagedGuardRtcHelpers.replace(
  "matches-prior? (= prior-view fresh-view)",
  "matches-prior? (and quiet-enough? (= prior-view fresh-view))",
);
const safeTrueWrappedPredicateRtcHelpers = safeNestedAndStateRtcHelpers.replace(
  "quiet-enough? (acceptable? fresh-view)",
  "quiet-enough? (true? (acceptable? fresh-view))",
);
const safeOrderedExhaustiveCondRtcHelpers = safeTrueWrappedPredicateRtcHelpers.replace(
  `(cond
                                (not quiet-enough?) nil
                                matches-prior? (or stable-since sampled-at)
                                :else sampled-at)`,
  `(cond
                                matches-prior? (or stable-since sampled-at)
                                quiet-enough? sampled-at
                                :else nil)`,
);
const completionOptionsForm = definitions(safeOptionTimedWindowFixture).get(
  "completion-window-options",
);
const safeMetadataDurationFixture = safeOptionTimedWindowFixture.replace(
  completionOptionsForm,
  `(def ^:private stable-window-ms 300)
(def ^{:private true} timeout-window-ms 5000)
(defn- completion-window-options []
  {:stable-ms stable-window-ms
   :timeout-ms timeout-window-ms})`,
);

const converged = (tx, blocks) => ({
  blocks,
  rtcTx: { localTx: tx, remoteTx: tx },
});

const firstStableCompletion = (samples, requiredStableSamples = 2) => {
  let previous;
  let stableSamples = 0;
  for (const [index, current] of samples.entries()) {
    const [page1, page2] = current;
    const contentsEqual = JSON.stringify(page1.blocks) === JSON.stringify(page2.blocks);
    const txConverged = [page1, page2].every(
      ({ rtcTx }) => rtcTx.localTx === rtcTx.remoteTx,
    );
    const unchanged = JSON.stringify(previous) === JSON.stringify(current);
    stableSamples = contentsEqual && txConverged && unchanged
      ? stableSamples + 1
      : 0;
    if (stableSamples >= requiredStableSamples) return index;
    previous = current;
  }
  throw new Error("completion barrier timeout");
};

test("late RTC transactions cannot satisfy the deterministic completion model", () => {
  const trace = [
    [converged(301, ["p1-r0-op48-nest"]), converged(301, [""])],
    [converged(303, ["p1-r0-op48-nest"]), converged(301, [""])],
    [converged(303, ["p1-r0-op48-nest"]), converged(303, ["p1-r0-op48-nest"])],
    [converged(305, ["p1-r0-op48-nest", "late"]), converged(303, ["p1-r0-op48-nest"])],
    [converged(305, ["p1-r0-op48-nest", "late"]), converged(305, ["p1-r0-op48-nest", "late"])],
    [converged(305, ["p1-r0-op48-nest", "late"]), converged(305, ["p1-r0-op48-nest", "late"])],
    [converged(305, ["p1-r0-op48-nest", "late"]), converged(305, ["p1-r0-op48-nest", "late"])],
  ];
  assert.equal(firstStableCompletion(trace), 6);
  assert.notDeepEqual(trace[0][0].blocks, trace[0][1].blocks);
  assert.equal(trace[0][0].rtcTx.localTx, trace[0][0].rtcTx.remoteTx);
});

test("the real stress test has a bounded two-client stable completion barrier", () => {
  assert.deepEqual(
    completionContractViolations(stressSource),
    [],
  );
});

test("contract accepts a safe synthetic barrier", () => {
  assert.deepEqual(
    completionContractViolations(safeFixture, safeRunner, safePrepush),
    [],
  );
});

test("contract accepts an equivalent shared RTC helper barrier", () => {
  assert.deepEqual(
    completionContractViolations(
      safeExternalHelperFixture,
      safeRunner,
      safePrepush,
      safeExternalRtcHelpers,
    ),
    [],
  );
});

test("contract discovers renamed causal triggers through helper indirection", () => {
  assert.deepEqual(
    completionContractViolations(
      safeRenamedIndirectFixture,
      safeRunner,
      safePrepush,
    ),
    [],
  );
});

test("contract evaluates a barrier nested in strict assertion arguments", () => {
  assert.deepEqual(
    completionContractViolations(
      safeAssertionArgumentFixture,
      safeRunner,
      safePrepush,
    ),
    [],
  );
});

test("contract follows invoked higher-order sampler and predicate symbols", () => {
  assert.deepEqual(
    completionContractViolations(
      safeHigherOrderFixture,
      safeRunner,
      safePrepush,
      safeHigherOrderRtcHelpers,
    ),
    [],
  );
});

test("contract accepts four-value tx equality and timed state stability", () => {
  assert.deepEqual(
    completionContractViolations(
      safeTimedWindowFixture,
      safeRunner,
      safePrepush,
      safeTimedWindowRtcHelpers,
    ),
    [],
  );
});

test("contract accepts restart timestamps and call-site duration options", () => {
  assert.deepEqual(
    completionContractViolations(
      safeOptionTimedWindowFixture,
      safeRunner,
      safePrepush,
      safeOptionTimedWindowRtcHelpers,
    ),
    [],
  );
});

test("contract follows an arbitrarily named sampled-state binding", () => {
  assert.deepEqual(
    completionContractViolations(
      safeOptionTimedWindowFixture,
      safeRunner,
      safePrepush,
      safeArbitraryStateRtcHelpers,
    ),
    [],
  );
});

test("contract accepts a staged stable-since success guard", () => {
  assert.deepEqual(
    completionContractViolations(
      safeOptionTimedWindowFixture,
      safeRunner,
      safePrepush,
      safeStagedGuardRtcHelpers,
    ),
    [],
  );
});

test("contract resolves metadata-bearing duration definitions", () => {
  assert.deepEqual(
    completionContractViolations(
      safeMetadataDurationFixture,
      safeRunner,
      safePrepush,
      safeStagedGuardRtcHelpers,
    ),
    [],
  );
});

test("contract accepts conjunctive nested state equality", () => {
  assert.deepEqual(
    completionContractViolations(
      safeMetadataDurationFixture,
      safeRunner,
      safePrepush,
      safeNestedAndStateRtcHelpers,
    ),
    [],
  );
});

test("contract accepts one true?-normalized state predicate", () => {
  assert.deepEqual(
    completionContractViolations(
      safeMetadataDurationFixture,
      safeRunner,
      safePrepush,
      safeTrueWrappedPredicateRtcHelpers,
    ),
    [],
  );
});

test("contract accepts an ordered exhaustive stability cond", () => {
  assert.deepEqual(
    completionContractViolations(
      safeMetadataDurationFixture,
      safeRunner,
      safePrepush,
      safeOrderedExhaustiveCondRtcHelpers,
    ),
    [],
  );
});

test("contract rejects unsafe completion and assertion mutations", () => {
  const mutations = [
    [
      "sleep only",
      safeFixture.replace(
        "(wait-for-two-client-fixpoint!)",
        "(util/wait-timeout 5000)",
      ),
      safeRunner,
      safePrepush,
    ],
    ["single client", safeFixture.replaceAll("@*page2", "@*page1"), safeRunner, safePrepush],
    [
      "single tx observation",
      safeFixture.replace(
        "(= previous current)",
        "(= (:rtc-tx page1-state) (:rtc-tx page2-state))",
      ),
      safeRunner,
      safePrepush,
    ],
    [
      "different final content accepted",
      safeFixture.replace(
        "(= (:blocks page1-state) (:blocks page2-state))",
        "(not= (:blocks page1-state) (:blocks page2-state))",
      ),
      safeRunner,
      safePrepush,
    ],
    [
      "fixed whole-test retry",
      safeFixture.replace(
        "(run-two-clients-in-parallel!)",
        "(dotimes [retry 3] (run-two-clients-in-parallel!))",
      ),
      safeRunner,
      safePrepush,
    ],
    [
      "runner retry",
      safeFixture,
      `${safeRunner}\nfor (let retry = 0; retry < 3; retry += 1) await runChild("bb", [testTask]);`,
      safePrepush,
    ],
    [
      "canonical real RTC runner path replaced",
      safeFixture,
      safeRunner.replace("rtc-extra-part2-test", "synthetic-rtc-test"),
      safePrepush.replace("rtc-extra-part2-test", "synthetic-rtc-test"),
    ],
    [
      "timeout swallowed",
      safeFixture.replace(
        '(throw (ex-info "completion barrier timeout" {:attempts 20}))',
        "nil",
      ),
      safeRunner,
      safePrepush,
    ],
    [
      "barrier exception swallowed",
      safeFixture.replace(
        "(wait-for-two-client-fixpoint!)",
        "(try (wait-for-two-client-fixpoint!) (catch Throwable _ nil))",
      ),
      safeRunner,
      safePrepush,
    ],
    [
      "original content assertion removed",
      safeFixture.replace(
        "(is (= (:blocks s1) (:blocks s2)))",
        "nil",
      ),
      safeRunner,
      safePrepush,
    ],
    [
      "original tx assertions removed",
      safeFixture
        .replace("(is (= (:local-tx tx1) (:remote-tx tx1)))", "nil")
        .replace("(is (= (:local-tx tx2) (:remote-tx tx2)))", "nil"),
      safeRunner,
      safePrepush,
    ],
    [
      "severe log assertion removed",
      safeFixture.replace("(assert-no-severe-sync-errors!)", "nil"),
      safeRunner,
      safePrepush,
    ],
    [
      "real RTC trigger removed",
      safeFixture.replace("(sync-by-trigger!)", "nil"),
      safeRunner,
      safePrepush,
    ],
    [
      "causal trigger primitive bypassed",
      safeFixture.replace(
        `(rtc/with-wait-tx-updated
    (new-block-safe! "completion-fence"))`,
        '(new-block-safe! "completion-fence")',
      ),
      safeRunner,
      safePrepush,
    ],
    [
      "renamed causal helper bypassed through indirection",
      safeRenamedIndirectFixture.replace(
        "  (establish-causal-watermark!)\n",
        "",
      ),
      safeRunner,
      safePrepush,
    ],
    [
      "assertion argument is only one two-client snapshot",
      safeAssertionArgumentFixture.replace(
        "(assert-two-pages-synced! (settle-after-concurrent-ops!))",
        "(assert-two-pages-synced! [(page-sync-state @*page1) (page-sync-state @*page2)])",
      ),
      safeRunner,
      safePrepush,
    ],
    [
      "assertion argument helper swallows timeout",
      safeAssertionArgumentFixture.replace(
        `(defn- settle-after-concurrent-ops! []
  (establish-causal-watermark!)
  (observe-two-client-fixpoint!))`,
        `(defn- settle-after-concurrent-ops! []
  (try
    (establish-causal-watermark!)
    (observe-two-client-fixpoint!)
    (catch Throwable _ nil)))`,
      ),
      safeRunner,
      safePrepush,
    ],
    [
      "assertion argument bypasses causal primitive",
      safeAssertionArgumentFixture.replace(
        `(rtc/with-wait-tx-updated
    (new-block-safe! "completion-fence"))`,
        '(new-block-safe! "completion-fence")',
      ),
      safeRunner,
      safePrepush,
    ],
    [
      "assertion body helper is not an evaluated completion barrier",
      safeAssertionArgumentFixture
        .replace(
          "(assert-two-pages-synced! (settle-after-concurrent-ops!))",
          "(assert-two-pages-synced! [(page-sync-state @*page1) (page-sync-state @*page2)])",
        )
        .replace(
          "(defn- assert-two-pages-synced! [_settled-state]",
          "(defn- assert-two-pages-synced! [_settled-state]\n  (settle-after-concurrent-ops!)",
        ),
      safeRunner,
      safePrepush,
    ],
    [
      "higher-order call passes a single-page sampler",
      safeHigherOrderFixture
        .replace(
          "(defn- sample-two-pages []",
          `(defn- sample-one-page []
  (w/with-page @*page1 (page-sync-state @*page1)))
(defn- sample-two-pages []`,
        )
        .replace(
          "      sample-two-pages\n      four-tx-and-blocks-converged?",
          "      sample-one-page\n      four-tx-and-blocks-converged?",
        ),
      safeRunner,
      safePrepush,
      safeHigherOrderRtcHelpers,
    ],
    [
      "higher-order predicate checks only tx convergence",
      safeHigherOrderFixture
        .replace(
          "(defn- four-tx-and-blocks-converged?",
          `(defn- tx-only-converged? [[page1-state page2-state]]
  (and (= (:local-tx (:rtc-tx page1-state)) (:remote-tx (:rtc-tx page1-state)))
       (= (:local-tx (:rtc-tx page2-state)) (:remote-tx (:rtc-tx page2-state)))))
(defn- four-tx-and-blocks-converged?`,
        )
        .replace(
          "      four-tx-and-blocks-converged?\n      #(Thread/sleep 100)",
          "      tx-only-converged?\n      #(Thread/sleep 100)",
        ),
      safeRunner,
      safePrepush,
      safeHigherOrderRtcHelpers,
    ],
    [
      "higher-order predicate checks only blocks",
      safeHigherOrderFixture
        .replace(
          "(defn- four-tx-and-blocks-converged?",
          `(defn- blocks-only-converged? [[page1-state page2-state]]
  (= (:blocks page1-state) (:blocks page2-state)))
(defn- four-tx-and-blocks-converged?`,
        )
        .replace(
          "      four-tx-and-blocks-converged?\n      #(Thread/sleep 100)",
          "      blocks-only-converged?\n      #(Thread/sleep 100)",
        ),
      safeRunner,
      safePrepush,
      safeHigherOrderRtcHelpers,
    ],
    [
      "higher-order stable waiter ignores its predicate",
      safeHigherOrderFixture,
      safeRunner,
      safePrepush,
      safeHigherOrderRtcHelpers.replace(
        "          acceptable-state? (acceptable? current)",
        "          acceptable-state? true",
      ),
    ],
    [
      "higher-order symbol resolves to a timeout-swallowing wrapper",
      safeHigherOrderFixture
        .replace(
          `(rtc/wait-for-stable-state!
      sample-two-pages
      four-tx-and-blocks-converged?
      #(Thread/sleep 100))`,
          "(invoke-waiter! swallowing-stable-waiter!)",
        )
        .replace(
          "(deftest online-two-clients-undo-redo-stress-test",
          `(defn- invoke-waiter! [waiter]
  (waiter))
(defn- swallowing-stable-waiter! []
  (try
    (rtc/wait-for-stable-state!
      sample-two-pages
      four-tx-and-blocks-converged?
      #(Thread/sleep 100))
    (catch Throwable _ nil)))
(deftest online-two-clients-undo-redo-stress-test`,
        ),
      safeRunner,
      safePrepush,
      safeHigherOrderRtcHelpers,
    ],
    [
      "apply equality receives only one client's two tx values",
      safeTimedWindowFixture.replace(
        `                   (:local-tx (:rtc-tx page2-state))
                   (:remote-tx (:rtc-tx page2-state))`,
        "",
      ),
      safeRunner,
      safePrepush,
      safeTimedWindowRtcHelpers,
    ],
    [
      "apply equality receives only three tx values",
      safeTimedWindowFixture.replace(
        "                   (:remote-tx (:rtc-tx page2-state))",
        "",
      ),
      safeRunner,
      safePrepush,
      safeTimedWindowRtcHelpers,
    ],
    [
      "apply equality pads a missing tx value with a constant",
      safeTimedWindowFixture.replace(
        "                   (:remote-tx (:rtc-tx page2-state))",
        "                   0",
      ),
      safeRunner,
      safePrepush,
      safeTimedWindowRtcHelpers,
    ],
    [
      "four tx values are only checked as nonnegative",
      safeTimedWindowFixture.replace(
        "(apply = tx-values)",
        "(every? #(>= % 0) tx-values)",
      ),
      safeRunner,
      safePrepush,
      safeTimedWindowRtcHelpers,
    ],
    [
      "timed barrier only sleeps for the stability duration",
      safeTimedWindowFixture,
      safeRunner,
      safePrepush,
      `(defn wait-for-stable-window! [sample acceptable? cadence]
  (let [stable-ms 300]
    (Thread/sleep stable-ms)
    (sample)))`,
    ],
    [
      "timed barrier does not reset after a different state",
      safeTimedWindowFixture,
      safeRunner,
      safePrepush,
      safeTimedWindowRtcHelpers.replace(
        "                                  (when acceptable-state? now)",
        "                                  stable-since",
      ),
    ],
    [
      "timed barrier treats timeout as successful completion",
      safeTimedWindowFixture,
      safeRunner,
      safePrepush,
      safeTimedWindowRtcHelpers.replace(
        '(throw (ex-info "completion barrier timeout" {:attempts 100}))',
        "previous-state",
      ),
    ],
    [
      "timed barrier uses a zero stability window",
      safeTimedWindowFixture,
      safeRunner,
      safePrepush,
      safeTimedWindowRtcHelpers.replace("stable-ms 300", "stable-ms 0"),
    ],
    [
      "changed state incorrectly preserves the old start timestamp",
      safeOptionTimedWindowFixture,
      safeRunner,
      safePrepush,
      safeOptionTimedWindowRtcHelpers.replace(
        ":else sampled-at",
        ":else stable-since",
      ),
    ],
    [
      "rejected state incorrectly restarts the stability timestamp",
      safeOptionTimedWindowFixture,
      safeRunner,
      safePrepush,
      safeOptionTimedWindowRtcHelpers.replace(
        "(not acceptable-state?) nil",
        "(not acceptable-state?) sampled-at",
      ),
    ],
    [
      "call-site swaps stable and timeout durations",
      safeOptionTimedWindowFixture
        .replace(":stable-ms stable-window-ms", ":stable-ms timeout-window-ms")
        .replace(":timeout-ms timeout-window-ms", ":timeout-ms stable-window-ms"),
      safeRunner,
      safePrepush,
      safeOptionTimedWindowRtcHelpers,
    ],
    [
      "call-site stability duration exceeds timeout",
      safeOptionTimedWindowFixture.replace(
        "stable-window-ms 300",
        "stable-window-ms 6000",
      ),
      safeRunner,
      safePrepush,
      safeOptionTimedWindowRtcHelpers,
    ],
    [
      "call-site stability duration is zero",
      safeOptionTimedWindowFixture.replace(
        "stable-window-ms 300",
        "stable-window-ms 0",
      ),
      safeRunner,
      safePrepush,
      safeOptionTimedWindowRtcHelpers,
    ],
    [
      "call-site timeout duration is negative",
      safeOptionTimedWindowFixture.replace(
        "timeout-window-ms 5000",
        "timeout-window-ms -1",
      ),
      safeRunner,
      safePrepush,
      safeOptionTimedWindowRtcHelpers,
    ],
    [
      "missing call-site stable option falls back to zero",
      safeOptionTimedWindowFixture.replace(
        "    {:stable-ms stable-window-ms\n     :timeout-ms timeout-window-ms}",
        "    {:timeout-ms timeout-window-ms}",
      ),
      safeRunner,
      safePrepush,
      safeOptionTimedWindowRtcHelpers,
    ],
    [
      "state comparison ignores the actual sampler result binding",
      safeOptionTimedWindowFixture,
      safeRunner,
      safePrepush,
      safeArbitraryStateRtcHelpers.replace(
        "matches-prior? (= prior-view fresh-view)",
        "matches-prior? (= prior-view unrelated-view)",
      ),
    ],
    [
      "unrelated since and elapsed values control staged success",
      safeOptionTimedWindowFixture,
      safeRunner,
      safePrepush,
      safeStagedGuardRtcHelpers
        .replace(
          `            stable-elapsed (if next-stable-since
                             (- sampled-at next-stable-since)
                             0)]`,
          `            stable-elapsed (if next-stable-since
                             (- sampled-at next-stable-since)
                             0)
            unrelated-since sampled-at
            unrelated-elapsed (- sampled-at unrelated-since)]`,
        )
        .replace(
          `(and next-stable-since
                 (>= stable-elapsed stable-ms))`,
          `(and unrelated-since
                 (>= unrelated-elapsed stable-ms))`,
        ),
    ],
    [
      "metadata-bearing stable duration is zero",
      safeMetadataDurationFixture.replace(
        "(def ^:private stable-window-ms 300)",
        "(def ^:private stable-window-ms 0)",
      ),
      safeRunner,
      safePrepush,
      safeStagedGuardRtcHelpers,
    ],
    [
      "metadata-bearing timeout duration is negative",
      safeMetadataDurationFixture.replace(
        "(def ^{:private true} timeout-window-ms 5000)",
        "(def ^{:private true} timeout-window-ms -1)",
      ),
      safeRunner,
      safePrepush,
      safeStagedGuardRtcHelpers,
    ],
    [
      "metadata-bearing stable duration is nonnumeric",
      safeMetadataDurationFixture.replace(
        "(def ^:private stable-window-ms 300)",
        '(def ^:private stable-window-ms "fast")',
      ),
      safeRunner,
      safePrepush,
      safeStagedGuardRtcHelpers,
    ],
    [
      "nested state equality is disjoined from acceptance",
      safeMetadataDurationFixture,
      safeRunner,
      safePrepush,
      safeNestedAndStateRtcHelpers.replace(
        "matches-prior? (and quiet-enough? (= prior-view fresh-view))",
        "matches-prior? (or quiet-enough? (= prior-view fresh-view))",
      ),
    ],
    [
      "nested state predicate has equality without acceptance",
      safeMetadataDurationFixture,
      safeRunner,
      safePrepush,
      safeNestedAndStateRtcHelpers.replace(
        "matches-prior? (and quiet-enough? (= prior-view fresh-view))",
        "matches-prior? (and (= prior-view fresh-view))",
      ),
    ],
    [
      "nested state predicate uses constant true acceptance",
      safeMetadataDurationFixture,
      safeRunner,
      safePrepush,
      safeNestedAndStateRtcHelpers.replace(
        "quiet-enough? (acceptable? fresh-view)",
        "quiet-enough? true",
      ),
    ],
    [
      "nested equality compares the wrong state binding",
      safeMetadataDurationFixture,
      safeRunner,
      safePrepush,
      safeNestedAndStateRtcHelpers.replace(
        "(= prior-view fresh-view)",
        "(= prior-view stable-since)",
      ),
    ],
    [
      "nested state equality is negated",
      safeMetadataDurationFixture,
      safeRunner,
      safePrepush,
      safeNestedAndStateRtcHelpers.replace(
        "(= prior-view fresh-view)",
        "(not (= prior-view fresh-view))",
      ),
    ],
    [
      "state predicate uses false? normalization",
      safeMetadataDurationFixture,
      safeRunner,
      safePrepush,
      safeTrueWrappedPredicateRtcHelpers.replace(
        "(true? (acceptable? fresh-view))",
        "(false? (acceptable? fresh-view))",
      ),
    ],
    [
      "state predicate negates the accepted predicate",
      safeMetadataDurationFixture,
      safeRunner,
      safePrepush,
      safeTrueWrappedPredicateRtcHelpers.replace(
        "(true? (acceptable? fresh-view))",
        "(not (acceptable? fresh-view))",
      ),
    ],
    [
      "true? normalization wraps a constant",
      safeMetadataDurationFixture,
      safeRunner,
      safePrepush,
      safeTrueWrappedPredicateRtcHelpers.replace(
        "(true? (acceptable? fresh-view))",
        "(true? true)",
      ),
    ],
    [
      "true? predicate observes a different state",
      safeMetadataDurationFixture,
      safeRunner,
      safePrepush,
      safeTrueWrappedPredicateRtcHelpers.replace(
        "(true? (acceptable? fresh-view))",
        "(true? (acceptable? prior-view))",
      ),
    ],
    [
      "true? predicate has a second arbitrary wrapper",
      safeMetadataDurationFixture,
      safeRunner,
      safePrepush,
      safeTrueWrappedPredicateRtcHelpers.replace(
        "(true? (acceptable? fresh-view))",
        "(true? (boolean (acceptable? fresh-view)))",
      ),
    ],
    [
      "ordered cond tests accepted before same state",
      safeMetadataDurationFixture,
      safeRunner,
      safePrepush,
      safeOrderedExhaustiveCondRtcHelpers.replace(
        `(cond
                                matches-prior? (or stable-since sampled-at)
                                quiet-enough? sampled-at
                                :else nil)`,
        `(cond
                                quiet-enough? sampled-at
                                matches-prior? (or stable-since sampled-at)
                                :else nil)`,
      ),
    ],
    [
      "ordered cond restarts rejected state at sampled time",
      safeMetadataDurationFixture,
      safeRunner,
      safePrepush,
      safeOrderedExhaustiveCondRtcHelpers.replace(
        ":else nil",
        ":else sampled-at",
      ),
    ],
    [
      "ordered cond preserves since for rejected state",
      safeMetadataDurationFixture,
      safeRunner,
      safePrepush,
      safeOrderedExhaustiveCondRtcHelpers.replace(
        ":else nil",
        ":else stable-since",
      ),
    ],
    [
      "ordered cond second branch lacks acceptance",
      safeMetadataDurationFixture,
      safeRunner,
      safePrepush,
      safeOrderedExhaustiveCondRtcHelpers.replace(
        "quiet-enough? sampled-at",
        "true sampled-at",
      ),
    ],
    [
      "ordered cond first predicate has equality without acceptance",
      safeMetadataDurationFixture,
      safeRunner,
      safePrepush,
      safeOrderedExhaustiveCondRtcHelpers.replace(
        "matches-prior? (and quiet-enough? (= prior-view fresh-view))",
        "matches-prior? (= prior-view fresh-view)",
      ),
    ],
  ];
  for (const [label, source, runner, prepush, rtcHelpers] of mutations) {
    assert.notDeepEqual(
      completionContractViolations(source, runner, prepush, rtcHelpers),
      [],
      `${label} unexpectedly satisfied the completion barrier contract`,
    );
  }
});
