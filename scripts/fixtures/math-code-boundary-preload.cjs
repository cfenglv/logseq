"use strict";

global.window = global;
global.document = {
  activeElement: null,
  body: {},
  documentElement: { currentStyle: {}, style: {} },
  createElement() {
    return {
      setAttribute() {},
      removeAttribute() {},
      style: {},
    };
  },
  querySelector() { return null; },
};
Object.defineProperty(global, "navigator", {
  configurable: true,
  value: { userAgent: "math-code-boundary-test" },
});
global.requestAnimationFrame = (callback) => callback();
