/**
 * Headless Automated Test Runner for OpenVScode Mobile Test Suite
 * Executes the full test suite in Node.js environment to verify all 10 test specs.
 */

const fs = require("fs");
const path = require("path");

// Mock browser DOM environment
class MockElement {
  constructor(tagName = "div", id = "") {
    this.tagName = tagName.toUpperCase();
    this.id = id;
    this._className = "";
    this.children = [];
    this.parentElement = null;
    this._textContent = "";
    this._innerHTML = "";
    this.value = "";
    this.selectionStart = 0;
    this.selectionEnd = 0;
    this.style = {};
    this.attributes = {};
    this.listeners = {};
    this.classList = {
      _classes: new Set(),
      add: (c) => {
        this.classList._classes.add(c);
        this._className = Array.from(this.classList._classes).join(" ");
      },
      remove: (c) => {
        this.classList._classes.delete(c);
        this._className = Array.from(this.classList._classes).join(" ");
      },
      toggle: (c, force) => {
        if (force === true) this.classList._classes.add(c);
        else if (force === false) this.classList._classes.delete(c);
        else if (this.classList._classes.has(c)) this.classList._classes.delete(c);
        else this.classList._classes.add(c);
        this._className = Array.from(this.classList._classes).join(" ");
      },
      contains: (c) => this.classList._classes.has(c),
    };
  }

  get className() {
    return this._className;
  }
  set className(val) {
    this._className = val || "";
    this.classList._classes.clear();
    this._className.split(/\s+/).filter(Boolean).forEach((c) => this.classList._classes.add(c));
  }

  get textContent() {
    if (this.children.length > 0) {
      return this.children.map((c) => c.textContent).join(" ");
    }
    return this._textContent;
  }
  set textContent(val) {
    this._textContent = val;
    this.children = [];
  }

  get innerHTML() {
    return this._innerHTML;
  }
  set innerHTML(val) {
    this._innerHTML = val;
    if (val === "") {
      this.children = [];
      this._textContent = "";
    }
  }

  setAttribute(name, val) {
    this.attributes[name] = val;
  }
  getAttribute(name) {
    return this.attributes[name] || null;
  }

  appendChild(child) {
    child.parentElement = this;
    this.children.push(child);
    return child;
  }

  removeChild(child) {
    const idx = this.children.indexOf(child);
    if (idx !== -1) {
      this.children.splice(idx, 1);
      child.parentElement = null;
    }
  }

  prepend(child) {
    child.parentElement = this;
    this.children.unshift(child);
  }

  contains(el) {
    if (el === this) return true;
    for (const c of this.children) {
      if (c === el || (c.contains && c.contains(el))) return true;
    }
    return false;
  }

  addEventListener(type, fn, options = {}) {
    if (!this.listeners[type]) this.listeners[type] = [];
    this.listeners[type].push({ fn, once: !!options.once });
  }

  removeEventListener(type, fn) {
    if (!this.listeners[type]) return;
    this.listeners[type] = this.listeners[type].filter((l) => l.fn !== fn);
  }

  dispatchEvent(evt) {
    if (!evt.target) {
      evt.target = this;
    }
    evt.currentTarget = this;
    const list = this.listeners[evt.type] ? [...this.listeners[evt.type]] : [];
    for (const l of list) {
      l.fn(evt);
      if (l.once) this.removeEventListener(evt.type, l.fn);
    }
    if (evt.bubbles && this.parentElement) {
      this.parentElement.dispatchEvent(evt);
    }
    return !evt.defaultPrevented;
  }

  click() {
    const pEvt = new PointerEvent("pointerdown", { bubbles: true, cancelable: true });
    this.dispatchEvent(pEvt);
    const evt = new MouseEvent("click", { bubbles: true, cancelable: true });
    this.dispatchEvent(evt);
  }

  focus() {
    global.document.activeElement = this;
    this.dispatchEvent(new Event("focus", { bubbles: false }));
    const focusInEvt = new Event("focusin", { bubbles: true });
    this.dispatchEvent(focusInEvt);
  }

  setRangeText(replacement, start, end, selectMode) {
    const before = this.value.substring(0, start);
    const after = this.value.substring(end);
    this.value = before + replacement + after;
    this.selectionStart = start + replacement.length;
    this.selectionEnd = start + replacement.length;
  }

  setSelectionRange(start, end) {
    this.selectionStart = start;
    this.selectionEnd = end;
  }

  getBoundingClientRect() {
    // Return mock dimensions
    const isPortrait = !this.classList.contains("landscape");
    if (this.id === "phoneChassis") {
      return { width: isPortrait ? 390 : 844, height: isPortrait ? 844 : 390 };
    }
    if (this.id === "appViewport") {
      const isKbOpen = global.document.getElementById("virtualKeyboard") && !global.document.getElementById("virtualKeyboard").classList.contains("hidden");
      return { width: isPortrait ? 390 : 844, height: isKbOpen ? 504 : 754 };
    }
    return { width: 100, height: 100 };
  }

  querySelectorAll(selector) {
    const results = [];
    const match = (el) => {
      if (selector.startsWith(".") && el.classList.contains(selector.substring(1))) {
        results.push(el);
      } else if (selector.startsWith("#") && el.id === selector.substring(1)) {
        results.push(el);
      }
      for (const c of el.children) match(c);
    };
    match(this);
    return results;
  }
}

// Global window & document
global.window = {
  addEventListener: (t, fn) => global.document.addEventListener(t, fn),
};
global.performance = { now: () => Date.now() };

class Event {
  constructor(type, opts = {}) {
    this.type = type;
    this.bubbles = !!opts.bubbles;
    this.cancelable = !!opts.cancelable;
    this.defaultPrevented = false;
  }
  preventDefault() {
    this.defaultPrevented = true;
  }
  stopPropagation() {}
}

class KeyboardEvent extends Event {
  constructor(type, opts = {}) {
    super(type, opts);
    this.key = opts.key || "";
    this.code = opts.code || "";
    this.ctrlKey = !!opts.ctrlKey;
    this.altKey = !!opts.altKey;
    this.shiftKey = !!opts.shiftKey;
  }
}

class InputEvent extends Event {
  constructor(type, opts = {}) {
    super(type, opts);
    this.data = opts.data || "";
    this.inputType = opts.inputType || "";
  }
}

class PointerEvent extends Event {}
class MouseEvent extends Event {}

global.Event = Event;
global.KeyboardEvent = KeyboardEvent;
global.InputEvent = InputEvent;
global.PointerEvent = PointerEvent;
global.MouseEvent = MouseEvent;

const domRegistry = {};

global.document = {
  body: new MockElement("body"),
  createElement: (tag) => new MockElement(tag),
  getElementById: (id) => domRegistry[id] || null,
  addEventListener: (t, fn) => global.document.body.addEventListener(t, fn),
  dispatchEvent: (e) => global.document.body.dispatchEvent(e),
  activeElement: null,
};

// Register elements present in index.html
const elementIds = [
  "phoneChassis", "appViewport", "editorPane", "terminalPane", "codeEditor",
  "lineNumbers", "nativeKeybar", "virtualKeyboard", "eventTableBody",
  "terminalInput", "terminalBody", "valOrientation", "valViewport",
  "valKeyboard", "valFocus", "valCaret", "valModifiers", "btnToggleOrientation",
  "btnToggleKeyboard", "btnResetEditor", "btnRunTests", "tabEditor",
  "tabTerminal", "valTestsPassed", "valTestsFailed", "testProgressFill", "testList"
];

elementIds.forEach((id) => {
  const el = new MockElement("div", id);
  if (id === "codeEditor" || id === "terminalInput") el.tagName = "TEXTAREA";
  if (id === "virtualKeyboard") el.classList.add("hidden");
  domRegistry[id] = el;
  global.document.body.appendChild(el);
});

const vm = require("vm");

// Load and evaluate simulator.js
const simCode = fs.readFileSync(path.join(__dirname, "simulator.js"), "utf8");
vm.runInThisContext(simCode);

// Instantiate MobileSimulator
global.simulator = new MobileSimulator();
global.simulator.updateViewportMetrics();

// Load and evaluate test-suite.js
const testCode = fs.readFileSync(path.join(__dirname, "test-suite.js"), "utf8");
vm.runInThisContext(testCode);



const suite = new MobileTestSuite(global.simulator);

console.log("================================================================================");
console.log("   RUNNING OPENVScode MOBILE AUTOMATED VERIFICATION TEST SUITE (HEADLESS)");
console.log("================================================================================");

async function run() {
  let passed = 0;
  let failed = 0;

  for (const test of suite.tests) {
    const t0 = Date.now();
    try {
      await test.fn();
      const elapsed = Date.now() - t0;
      console.log(`  ✓ PASS: [${test.id}] ${test.name} (${elapsed}ms)`);
      passed++;
    } catch (err) {
      const elapsed = Date.now() - t0;
      console.error(`  ✗ FAIL: [${test.id}] ${test.name} (${elapsed}ms)`);
      console.error(`          Reason: ${err.message}`);
      failed++;
    }
  }

  console.log("================================================================================");
  console.log(`   FINAL TEST RESULTS: ${passed} PASSED, ${failed} FAILED (TOTAL: ${suite.tests.length})`);
  console.log("================================================================================");

  if (failed > 0) {
    process.exit(1);
  } else {
    process.exit(0);
  }
}

run();
