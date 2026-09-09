/**
 * OpenVScode Mobile Simulator Core Engine
 * Manages device viewport, virtual keyboard adjustResize,
 * key injection, caret tracking, and live diagnostics.
 */

class MobileSimulator {
  constructor() {
    this.orientation = "portrait"; // portrait or landscape
    this.isKeyboardOpen = false;
    this.ctrlActive = false;
    this.altActive = false;
    this.currentActiveElement = null;
    this.lastEditorCaret = { start: 0, end: 0 };
    this.activeTab = "editor"; // editor or terminal
    this.eventLogs = [];
    this.maxLogs = 200;

    this.sampleCppCode = `// OpenVScode Mobile C++ Kernel
#include <iostream>
#include <vector>
#include <string>

int main(int argc, char* argv[]) {
    std::cout << "OpenVScode Android Native Engine" << std::endl;
    std::vector<std::string> tools = {"Clang", "Python", "Jupyter"};

    for (const auto& tool : tools) {
        std::cout << "  - Loaded: " << tool << std::endl;
    }

    return 0;
}
`;

    this.samplePyCode = `# OpenVScode Mobile Python Engine
import sys
import os

def check_environment():
    print(f"Python: {sys.version}")
    print(f"Platform: {sys.platform}")
    print("OpenVScode mobile server online on port 8080")

if __name__ == "__main__":
    check_environment()
`;

    this.initDOM();
    this.bindEvents();
    this.setupKeybar();
    this.updateLineNumbers();
  }

  initDOM() {
    this.chassis = document.getElementById("phoneChassis");
    this.viewport = document.getElementById("appViewport");
    this.editorPane = document.getElementById("editorPane");
    this.terminalPane = document.getElementById("terminalPane");
    this.codeEditor = document.getElementById("codeEditor");
    this.lineNumbers = document.getElementById("lineNumbers");
    this.nativeKeybar = document.getElementById("nativeKeybar");
    this.virtualKeyboard = document.getElementById("virtualKeyboard");
    this.eventTableBody = document.getElementById("eventTableBody");
    this.terminalInput = document.getElementById("terminalInput");
    this.terminalBody = document.getElementById("terminalBody");

    // Metrics display elements
    this.valOrientation = document.getElementById("valOrientation");
    this.valViewport = document.getElementById("valViewport");
    this.valKeyboard = document.getElementById("valKeyboard");
    this.valFocus = document.getElementById("valFocus");
    this.valCaret = document.getElementById("valCaret");
    this.valModifiers = document.getElementById("valModifiers");

    // Set initial editor content
    if (this.codeEditor) {
      this.codeEditor.value = this.sampleCppCode;
      // Position caret inside main()
      const mainIdx = this.codeEditor.value.indexOf("std::cout << \"OpenVScode");
      if (mainIdx !== -1) {
        this.codeEditor.setSelectionRange(mainIdx, mainIdx);
      }
      this.currentActiveElement = this.codeEditor;
    }
  }

  bindEvents() {
    // Orientation toggle
    const btnOrientation = document.getElementById("btnToggleOrientation");
    if (btnOrientation) {
      btnOrientation.addEventListener("click", () => this.toggleOrientation());
    }

    // Keyboard toggle
    const btnKeyboard = document.getElementById("btnToggleKeyboard");
    if (btnKeyboard) {
      btnKeyboard.addEventListener("click", () => this.toggleVirtualKeyboard());
    }

    // Tab switching (Editor vs Terminal)
    const tabEditor = document.getElementById("tabEditor");
    const tabTerminal = document.getElementById("tabTerminal");

    if (tabEditor && tabTerminal) {
      tabEditor.addEventListener("click", () => this.switchTab("editor"));
      tabTerminal.addEventListener("click", () => this.switchTab("terminal"));
    }

    // Code Editor tracking
    if (this.codeEditor) {
      this.codeEditor.addEventListener("input", () => {
        this.updateLineNumbers();
        this.updateCaretMetrics();
      });

      this.codeEditor.addEventListener("click", () => this.updateCaretMetrics());
      this.codeEditor.addEventListener("keyup", () => this.updateCaretMetrics());
      this.codeEditor.addEventListener("select", () => this.updateCaretMetrics());

      this.codeEditor.addEventListener("focus", () => {
        this.currentActiveElement = this.codeEditor;
        this.updateFocusMetrics();
      });
    }

    // Terminal Input tracking
    if (this.terminalInput) {
      this.terminalInput.addEventListener("focus", () => {
        this.currentActiveElement = this.terminalInput;
        this.updateFocusMetrics();
      });

      this.terminalInput.addEventListener("keydown", (e) => {
        if (e.key === "Enter") {
          e.preventDefault();
          this.executeTerminalCommand(this.terminalInput.value);
          this.terminalInput.value = "";
        }
      });
    }

    // Global listener to capture events for the Diagnostics Console
    window.addEventListener(
      "keydown",
      (e) => {
        this.logEvent("keydown", e);
      },
      true
    );

    window.addEventListener(
      "keyup",
      (e) => {
        this.logEvent("keyup", e);
      },
      true
    );

    window.addEventListener(
      "input",
      (e) => {
        this.logEvent("input", e);
      },
      true
    );

    // Global focus tracker
    document.addEventListener("focusin", (e) => {
      if (
        this.nativeKeybar &&
        !this.nativeKeybar.contains(e.target) &&
        this.virtualKeyboard &&
        !this.virtualKeyboard.contains(e.target)
      ) {
        this.currentActiveElement = e.target;
        this.updateFocusMetrics();
      }
    });

    // Virtual Keyboard Key Clicks
    if (this.virtualKeyboard) {
      this.virtualKeyboard.addEventListener("pointerdown", (e) => {
        e.preventDefault(); // Don't blur editor on keyboard tap!
      });
      this.virtualKeyboard.addEventListener("mousedown", (e) => {
        e.preventDefault();
      });

      const keys = this.virtualKeyboard.querySelectorAll(".kb-key");
      keys.forEach((keyEl) => {
        keyEl.addEventListener("click", (e) => {
          e.preventDefault();
          const action = keyEl.getAttribute("data-action");
          const char = keyEl.getAttribute("data-char") || keyEl.textContent.trim();

          if (action === "hide") {
            this.setVirtualKeyboard(false);
          } else if (action === "backspace") {
            this.injectBackspace();
          } else if (action === "enter") {
            this.injectEnter();
          } else if (action === "space") {
            this.injectText(" ");
          } else {
            this.injectText(char);
          }
        });
      });
    }
  }

  toggleOrientation() {
    this.orientation = this.orientation === "portrait" ? "landscape" : "portrait";
    this.chassis.classList.remove("portrait", "landscape");
    this.chassis.classList.add(this.orientation);

    const btn = document.getElementById("btnToggleOrientation");
    if (btn) {
      btn.innerHTML =
        this.orientation === "portrait"
          ? `<span class="icon">📱</span> Portrait (390×844)`
          : `<span class="icon">🔄</span> Landscape (844×390)`;
    }

    this.updateViewportMetrics();
  }

  toggleVirtualKeyboard() {
    this.setVirtualKeyboard(!this.isKeyboardOpen);
  }

  setVirtualKeyboard(open) {
    this.isKeyboardOpen = open;
    const btn = document.getElementById("btnToggleKeyboard");

    if (open) {
      this.virtualKeyboard.classList.remove("hidden");
      if (btn) btn.classList.add("active");
    } else {
      this.virtualKeyboard.classList.add("hidden");
      if (btn) btn.classList.remove("active");
    }

    this.updateViewportMetrics();
  }

  switchTab(tab) {
    this.activeTab = tab;
    const tabEditor = document.getElementById("tabEditor");
    const tabTerminal = document.getElementById("tabTerminal");

    if (tab === "editor") {
      this.editorPane.classList.remove("hidden");
      this.terminalPane.classList.add("hidden");
      tabEditor.classList.add("active");
      tabTerminal.classList.remove("active");
      this.codeEditor.focus();
    } else {
      this.editorPane.classList.add("hidden");
      this.terminalPane.classList.remove("hidden");
      tabEditor.classList.remove("active");
      tabTerminal.classList.add("active");
      this.terminalInput.focus();
    }
  }

  setupKeybar() {
    if (!this.nativeKeybar) return;
    this.nativeKeybar.innerHTML = "";

    const keyDefs = [
      { label: "ESC", key: "Escape" },
      { label: "TAB", key: "Tab" },
      { label: "CTRL", key: "Control", modifier: true },
      { label: "ALT", key: "Alt", modifier: true },
      { label: "{", char: "{" },
      { label: "}", char: "}" },
      { label: "(", char: "(" },
      { label: ")", char: ")" },
      { label: "[", char: "[" },
      { label: "]", char: "]" },
      { label: ";", char: ";" },
      { label: ":", char: ":" },
      { label: "=", char: "=" },
      { label: '"', char: '"' },
      { label: "'", char: "'" },
      { label: "/", char: "/" },
      { label: "\\", char: "\\" },
      { label: "|", char: "|" },
      { label: "_", char: "_" },
      { label: "←", key: "ArrowLeft" },
      { label: "↑", key: "ArrowUp" },
      { label: "↓", key: "ArrowDown" },
      { label: "→", key: "ArrowRight" },
    ];

    keyDefs.forEach((k) => {
      const btn = document.createElement("button");
      btn.className = "keybar-btn" + (k.modifier ? " modifier" : "");
      btn.textContent = k.label;
      btn.setAttribute("data-key", k.key || k.char);

      if (k.label === "CTRL") this.ctrlBtn = btn;
      if (k.label === "ALT") this.altBtn = btn;

      // CRITICAL: Prevent stealing focus on touch or mouse press
      btn.addEventListener("pointerdown", (e) => {
        e.preventDefault();
      });
      btn.addEventListener("mousedown", (e) => {
        e.preventDefault();
      });

      btn.addEventListener("click", (e) => {
        e.preventDefault();
        e.stopPropagation();
        this.handleKeybarPress(k);
      });

      this.nativeKeybar.appendChild(btn);
    });
  }

  handleKeybarPress(k) {
    // 1. Modifiers toggle
    if (k.label === "CTRL") {
      this.ctrlActive = !this.ctrlActive;
      this.ctrlBtn.classList.toggle("active", this.ctrlActive);
      this.updateModifierMetrics();
      return;
    }

    if (k.label === "ALT") {
      this.altActive = !this.altActive;
      this.altBtn.classList.toggle("active", this.altActive);
      this.updateModifierMetrics();
      return;
    }

    // 2. Resolve Target Element
    let target = this.currentActiveElement;
    if (!target || !document.body.contains(target)) {
      target = this.activeTab === "terminal" ? this.terminalInput : this.codeEditor;
    }

    // Ensure target remains focused without jumping
    if (document.activeElement !== target && typeof target.focus === "function") {
      target.focus();
    }

    const isModifierActive = this.ctrlActive || this.altActive;

    // 3. Dispatch Key Events or Printable Text
    if (k.char && !isModifierActive) {
      this.injectText(k.char, target);
    } else {
      const keyValue = k.char || k.key;
      const keyCode = k.key || ("Key" + keyValue.toUpperCase());

      // Keydown Event
      const evtDown = new KeyboardEvent("keydown", {
        key: keyValue,
        code: keyCode,
        ctrlKey: this.ctrlActive,
        altKey: this.altActive,
        bubbles: true,
        cancelable: true,
      });
      target.dispatchEvent(evtDown);

      // Handle TAB / Arrows if not prevented
      if (!isModifierActive) {
        if (k.key === "Tab") {
          this.injectText("    ", target);
        } else if (k.key === "ArrowLeft") {
          this.moveCaret(-1, target);
        } else if (k.key === "ArrowRight") {
          this.moveCaret(1, target);
        } else if (k.key === "ArrowUp") {
          this.moveCaretLine(-1, target);
        } else if (k.key === "ArrowDown") {
          this.moveCaretLine(1, target);
        }
      }

      // Keyup Event
      const evtUp = new KeyboardEvent("keyup", {
        key: keyValue,
        code: keyCode,
        ctrlKey: this.ctrlActive,
        altKey: this.altActive,
        bubbles: true,
        cancelable: true,
      });
      target.dispatchEvent(evtUp);
    }

    // 4. Reset modifiers after use
    if (this.ctrlActive) {
      this.ctrlActive = false;
      if (this.ctrlBtn) this.ctrlBtn.classList.remove("active");
    }
    if (this.altActive) {
      this.altActive = false;
      if (this.altBtn) this.altBtn.classList.remove("active");
    }
    this.updateModifierMetrics();
    this.updateCaretMetrics();
  }

  injectText(text, targetEl) {
    const target = targetEl || this.currentActiveElement || this.codeEditor;
    if (!target) return;

    if (typeof target.setRangeText === "function") {
      const start = target.selectionStart;
      const end = target.selectionEnd;
      target.setRangeText(text, start, end, "end");
      target.dispatchEvent(
        new InputEvent("input", {
          bubbles: true,
          cancelable: false,
          inputType: "insertText",
          data: text,
        })
      );
    } else {
      document.execCommand("insertText", false, text);
    }

    this.updateLineNumbers();
    this.updateCaretMetrics();
  }

  injectBackspace(targetEl) {
    const target = targetEl || this.currentActiveElement || this.codeEditor;
    if (!target || typeof target.setRangeText !== "function") return;

    const start = target.selectionStart;
    const end = target.selectionEnd;

    if (start === end && start > 0) {
      target.setRangeText("", start - 1, start, "end");
    } else if (start !== end) {
      target.setRangeText("", start, end, "end");
    }

    target.dispatchEvent(
      new InputEvent("input", {
        bubbles: true,
        inputType: "deleteContentBackward",
      })
    );

    this.updateLineNumbers();
    this.updateCaretMetrics();
  }

  injectEnter(targetEl) {
    const target = targetEl || this.currentActiveElement || this.codeEditor;
    if (!target) return;

    if (target === this.terminalInput) {
      this.executeTerminalCommand(target.value);
      target.value = "";
    } else {
      this.injectText("\n", target);
    }
  }

  moveCaret(offset, targetEl) {
    const target = targetEl || this.currentActiveElement || this.codeEditor;
    if (!target || typeof target.setSelectionRange !== "function") return;

    const pos = Math.max(0, Math.min(target.value.length, target.selectionStart + offset));
    target.setSelectionRange(pos, pos);
    this.updateCaretMetrics();
  }

  moveCaretLine(direction, targetEl) {
    const target = targetEl || this.currentActiveElement || this.codeEditor;
    if (!target || typeof target.setSelectionRange !== "function") return;

    const text = target.value;
    const pos = target.selectionStart;
    const lines = text.split("\n");

    let currentLine = 0;
    let charCount = 0;
    let col = 0;

    for (let i = 0; i < lines.length; i++) {
      const lineLen = lines[i].length + 1; // +1 for \n
      if (charCount + lineLen > pos) {
        currentLine = i;
        col = pos - charCount;
        break;
      }
      charCount += lineLen;
    }

    const targetLine = currentLine + direction;
    if (targetLine < 0 || targetLine >= lines.length) return;

    let newCharCount = 0;
    for (let i = 0; i < targetLine; i++) {
      newCharCount += lines[i].length + 1;
    }

    const targetCol = Math.min(col, lines[targetLine].length);
    const newPos = newCharCount + targetCol;

    target.setSelectionRange(newPos, newPos);
    this.updateCaretMetrics();
  }

  updateLineNumbers() {
    if (!this.codeEditor || !this.lineNumbers) return;
    const lines = this.codeEditor.value.split("\n").length;
    let numStr = "";
    for (let i = 1; i <= lines; i++) {
      numStr += i + "<br>";
    }
    this.lineNumbers.innerHTML = numStr;
  }

  updateCaretMetrics() {
    const target = this.currentActiveElement || this.codeEditor;
    if (!target || typeof target.selectionStart !== "number") return;

    const pos = target.selectionStart;
    const text = target.value || "";
    const lines = text.substring(0, pos).split("\n");
    const line = lines.length;
    const col = lines[lines.length - 1].length + 1;

    if (this.valCaret) {
      this.valCaret.textContent = `Ln ${line}, Col ${col} (${pos})`;
    }
  }

  updateFocusMetrics() {
    if (!this.valFocus) return;
    const el = this.currentActiveElement;
    if (!el) {
      this.valFocus.textContent = "None";
    } else if (el === this.codeEditor) {
      this.valFocus.textContent = "#codeEditor (Code)";
    } else if (el === this.terminalInput) {
      this.valFocus.textContent = "#terminalInput (Shell)";
    } else {
      this.valFocus.textContent = el.tagName.toLowerCase();
    }
  }

  updateViewportMetrics() {
    if (!this.valViewport || !this.viewport) return;
    const rect = this.viewport.getBoundingClientRect();
    this.valViewport.textContent = `${Math.round(rect.width)} × ${Math.round(rect.height)} px`;

    if (this.valOrientation) {
      this.valOrientation.textContent = this.orientation.toUpperCase();
    }

    if (this.valKeyboard) {
      this.valKeyboard.textContent = this.isKeyboardOpen ? "VISIBLE (adjustResize)" : "HIDDEN";
      this.valKeyboard.style.color = this.isKeyboardOpen ? "#4ec9b0" : "#cccccc";
    }
  }

  updateModifierMetrics() {
    if (!this.valModifiers) return;
    const mods = [];
    if (this.ctrlActive) mods.push("CTRL");
    if (this.altActive) mods.push("ALT");
    this.valModifiers.textContent = mods.length > 0 ? mods.join(" + ") : "NONE";
    this.valModifiers.style.color = mods.length > 0 ? "#0098ff" : "#cccccc";
  }

  executeTerminalCommand(cmd) {
    const cleanCmd = cmd.trim();
    if (!cleanCmd) return;

    this.appendTerminalLine(`openvscode@android:~/workspace$ ${cleanCmd}`, "terminal-prompt");

    if (cleanCmd === "clear") {
      this.terminalBody.innerHTML = "";
    } else if (cleanCmd === "ls") {
      this.appendTerminalLine("main.cpp  main.py  Makefile  README.md  build/");
    } else if (cleanCmd === "pwd") {
      this.appendTerminalLine("/data/data/com.openvscode.mobile/files/home/workspace");
    } else if (cleanCmd.startsWith("clang++") || cleanCmd.includes("main.cpp")) {
      this.appendTerminalLine("[clang++] Compiling main.cpp with -O3 -std=c++20 ...");
      this.appendTerminalLine("OpenVScode Android Native Engine");
      this.appendTerminalLine("  - Loaded: Clang");
      this.appendTerminalLine("  - Loaded: Python");
      this.appendTerminalLine("  - Loaded: Jupyter");
      this.appendTerminalLine("[Process exited with status 0]");
    } else if (cleanCmd.startsWith("python") || cleanCmd.includes("main.py")) {
      this.appendTerminalLine("Python: 3.11.15 (main, default)");
      this.appendTerminalLine("Platform: linux (Android aarch64)");
      this.appendTerminalLine("OpenVScode mobile server online on port 8080");
    } else {
      this.appendTerminalLine(`Command executed: ${cleanCmd}`);
    }

    this.terminalBody.scrollTop = this.terminalBody.scrollHeight;
  }

  appendTerminalLine(text, className) {
    const div = document.createElement("div");
    if (className) div.className = className;
    div.textContent = text;
    this.terminalBody.appendChild(div);
  }

  logEvent(type, event) {
    if (!this.eventTableBody) return;

    const row = document.createElement("tr");
    const time = new Date().toISOString().substring(17, 23);
    const key = event.key || event.data || "-";
    const code = event.code || "-";
    const targetTag = (event.target && (event.target.id || event.target.tagName)) || "-";
    const ctrl = event.ctrlKey ? "Y" : "-";
    const alt = event.altKey ? "Y" : "-";

    row.innerHTML = `
      <td>${time}</td>
      <td style="color: ${type === "keydown" ? "#4ec9b0" : type === "keyup" ? "#ce9178" : "#9cdcfe"}">${type}</td>
      <td><strong>${key}</strong></td>
      <td>${code}</td>
      <td>${ctrl}/${alt}</td>
      <td>#${targetTag}</td>
    `;

    this.eventTableBody.prepend(row);

    // Keep log limited to prevent slowdown
    if (this.eventTableBody.children.length > this.maxLogs) {
      this.eventTableBody.removeChild(this.eventTableBody.lastChild);
    }
  }

  clearLogs() {
    if (this.eventTableBody) {
      this.eventTableBody.innerHTML = "";
    }
  }
}

// Global simulator instance
window.simulator = null;
document.addEventListener("DOMContentLoaded", () => {
  window.simulator = new MobileSimulator();
  window.simulator.updateViewportMetrics();
  window.simulator.updateCaretMetrics();
  window.simulator.updateFocusMetrics();
});
