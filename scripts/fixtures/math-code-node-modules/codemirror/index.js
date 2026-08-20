function CodeMirror() {}
CodeMirror.Pass = {};
CodeMirror.Pos = function Pos(line, ch) { return { line, ch }; };
CodeMirror.fromTextArea = function fromTextArea() {
  throw new Error("boundary test must redefine fromTextArea");
};
CodeMirror.registerHelper = function registerHelper() {};
CodeMirror.findModeByName = function findModeByName() { return null; };
CodeMirror.findModeByExtension = function findModeByExtension() { return null; };
CodeMirror.findModeByFileName = function findModeByFileName() { return null; };
module.exports = CodeMirror;
