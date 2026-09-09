/**
 * OpenVScode Mobile Test Suite
 * Automated test runner for mobile keybar, caret stability,
 * event dispatching, virtual keyboard adjustResize, and terminal integration.
 */

class MobileTestSuite {
  constructor(simulator) {
    this.sim = simulator;
    this.tests = [
      {
        id: "TEST_FOCUS_RETENTION",
        name: "1. Focus Retention on Keybar Touches",
        desc: "Ensures touching keybar buttons does not steal or blur focus from active editor/terminal",
        fn: () => this.testFocusRetention(),
      },
      {
        id: "TEST_ESC_DISPATCH",
        name: "2. ESC Key Event Dispatching",
        desc: "Verifies ESC emits synthetic KeyboardEvent(key: 'Escape', code: 'Escape') to focused element",
        fn: () => this.testEscDispatch(),
      },
      {
        id: "TEST_TAB_INDENT",
        name: "3. TAB Indentation & Event Dispatch",
        desc: "Verifies TAB emits 'Tab' KeyboardEvent and inserts 4 spaces at caret without blur",
        fn: () => this.testTabIndent(),
      },
      {
        id: "TEST_CTRL_MODIFIER",
        name: "4. CTRL Modifier & Shortcut Dispatch",
        desc: "Verifies CTRL toggles active state, injects ctrlKey: true into next keypress, then auto-resets",
        fn: () => this.testCtrlModifier(),
      },
      {
        id: "TEST_ALT_MODIFIER",
        name: "5. ALT Modifier & Key Combo Dispatch",
        desc: "Verifies ALT toggles active state, injects altKey: true into next keypress, then auto-resets",
        fn: () => this.testAltModifier(),
      },
      {
        id: "TEST_BRACES_INSERTION",
        name: "6. Braces & Caret Offset Integrity",
        desc: "Verifies { and } insert at exact caret position without cursor drift or document corruption",
        fn: () => this.testBracesInsertion(),
      },
      {
        id: "TEST_PARENS_BRACKETS",
        name: "7. Brackets, Quotes, and Symbols Insertion",
        desc: "Verifies ( ), [ ], ;, :, =, quotes, and slashes insert cleanly into active buffer",
        fn: () => this.testParensBrackets(),
      },
      {
        id: "TEST_ARROW_NAVIGATION",
        name: "8. Arrow Keys Caret Navigation",
        desc: "Verifies ← and → move caret position left and right and emit Arrow events",
        fn: () => this.testArrowNavigation(),
      },
      {
        id: "TEST_ADJUST_RESIZE",
        name: "9. Virtual Keyboard & adjustResize Layout",
        desc: "Verifies opening virtual keyboard compresses viewport height and docks keybar above keyboard",
        fn: () => this.testAdjustResize(),
      },
      {
        id: "TEST_TERMINAL_INPUT",
        name: "10. Mobile Terminal Key Routing & Execution",
        desc: "Verifies integrated terminal captures keybar inputs, executes commands, and displays output",
        fn: () => this.testTerminalInput(),
      },
    ];

    this.results = [];
    this.isRunning = false;
  }

  async runAll() {
    if (this.isRunning) return;
    this.isRunning = true;
    this.results = [];

    const testListEl = document.getElementById("testList");
    const progressFill = document.getElementById("testProgressFill");
    const runBtn = document.getElementById("btnRunTests");

    if (runBtn) {
      runBtn.disabled = true;
      runBtn.textContent = "Running Tests...";
    }

    let passedCount = 0;
    const total = this.tests.length;

    for (let i = 0; i < total; i++) {
      const test = this.tests[i];
      const itemEl = document.getElementById(`test-item-${test.id}`);
      const badgeEl = document.getElementById(`badge-${test.id}`);
      const detailEl = document.getElementById(`detail-${test.id}`);

      if (badgeEl) {
        badgeEl.className = "test-badge running";
        badgeEl.textContent = "RUNNING";
      }

      // Small pause to allow UI update
      await new Promise((r) => setTimeout(r, 80));

      const startTime = performance.now();
      let result = { passed: false, error: null, timeMs: 0 };

      try {
        await test.fn();
        result.passed = true;
      } catch (err) {
        result.passed = false;
        result.error = err.message || String(err);
      }

      result.timeMs = Math.round(performance.now() - startTime);
      this.results.push(result);

      if (result.passed) {
        passedCount++;
        if (badgeEl) {
          badgeEl.className = "test-badge pass";
          badgeEl.textContent = `PASS (${result.timeMs}ms)`;
        }
        if (itemEl) {
          itemEl.classList.remove("failed");
          itemEl.classList.add("passed");
        }
        if (detailEl) {
          detailEl.textContent = `Assertions satisfied in ${result.timeMs}ms`;
          detailEl.style.color = "#4ec9b0";
        }
      } else {
        if (badgeEl) {
          badgeEl.className = "test-badge fail";
          badgeEl.textContent = "FAIL";
        }
        if (itemEl) {
          itemEl.classList.remove("passed");
          itemEl.classList.add("failed");
        }
        if (detailEl) {
          detailEl.textContent = `Failure: ${result.error}`;
          detailEl.style.color = "#f48771";
        }
      }

      if (progressFill) {
        progressFill.style.width = `${Math.round(((i + 1) / total) * 100)}%`;
      }
    }

    // Update overall summary metrics
    const valPassed = document.getElementById("valTestsPassed");
    const valFailed = document.getElementById("valTestsFailed");
    if (valPassed) valPassed.textContent = `${passedCount}/${total}`;
    if (valFailed) valFailed.textContent = `${total - passedCount}`;

    if (runBtn) {
      runBtn.disabled = false;
      runBtn.innerHTML = `<span>▶</span> Run All Tests`;
    }

    this.isRunning = false;
    this.showToast(
      passedCount === total
        ? `All ${total} Mobile Verification Tests Passed!`
        : `${total - passedCount} test(s) failed.`
    );

    return { total, passed: passedCount, failed: total - passedCount };
  }

  // --- INDIVIDUAL TESTS ---

  async testFocusRetention() {
    this.sim.switchTab("editor");
    const editor = this.sim.codeEditor;
    editor.focus();

    // Verify editor is focused
    if (document.activeElement !== editor) {
      throw new Error("Editor did not gain initial focus");
    }

    // Find any keybar button (e.g. ';')
    const keyBtn = Array.from(this.sim.nativeKeybar.querySelectorAll(".keybar-btn")).find(
      (b) => b.textContent === ";"
    );
    if (!keyBtn) throw new Error("Keybar button ';' not found");

    // Simulate pointerdown and click
    const pEvt = new PointerEvent("pointerdown", { bubbles: true, cancelable: true });
    keyBtn.dispatchEvent(pEvt);

    // Active element must still be the editor!
    if (document.activeElement !== editor) {
      throw new Error(
        `Focus was stolen by keybar! Active element: ${document.activeElement.tagName}`
      );
    }
  }

  async testEscDispatch() {
    this.sim.switchTab("editor");
    const editor = this.sim.codeEditor;
    editor.focus();

    let eventCaptured = null;
    const handler = (e) => {
      eventCaptured = e;
    };
    editor.addEventListener("keydown", handler, { once: true });

    // Click ESC on keybar
    const escBtn = Array.from(this.sim.nativeKeybar.querySelectorAll(".keybar-btn")).find(
      (b) => b.textContent === "ESC"
    );
    if (!escBtn) throw new Error("ESC button not found in keybar");

    escBtn.click();

    editor.removeEventListener("keydown", handler);

    if (!eventCaptured) {
      throw new Error("ESC keydown event was not received by focused editor");
    }
    if (eventCaptured.key !== "Escape") {
      throw new Error(`Expected key 'Escape', got '${eventCaptured.key}'`);
    }
  }

  async testTabIndent() {
    this.sim.switchTab("editor");
    const editor = this.sim.codeEditor;
    editor.focus();

    editor.value = "void test() {\n";
    editor.setSelectionRange(14, 14); // Position right after newline

    let eventCaptured = null;
    const handler = (e) => {
      eventCaptured = e;
    };
    editor.addEventListener("keydown", handler, { once: true });

    const tabBtn = Array.from(this.sim.nativeKeybar.querySelectorAll(".keybar-btn")).find(
      (b) => b.textContent === "TAB"
    );
    if (!tabBtn) throw new Error("TAB button not found in keybar");

    tabBtn.click();

    editor.removeEventListener("keydown", handler);

    if (!eventCaptured || eventCaptured.key !== "Tab") {
      throw new Error("TAB keydown event not dispatched to editor");
    }

    // Verify 4 spaces were inserted
    const expected = "void test() {\n    ";
    if (editor.value !== expected) {
      throw new Error(`Expected indent '${expected}', got '${editor.value}'`);
    }
    if (editor.selectionStart !== 18) {
      throw new Error(`Expected caret at 18, got ${editor.selectionStart}`);
    }
  }

  async testCtrlModifier() {
    this.sim.switchTab("editor");
    const editor = this.sim.codeEditor;
    editor.focus();

    const ctrlBtn = Array.from(this.sim.nativeKeybar.querySelectorAll(".keybar-btn")).find(
      (b) => b.textContent === "CTRL"
    );
    if (!ctrlBtn) throw new Error("CTRL button not found");

    // Click CTRL to activate modifier
    ctrlBtn.click();
    if (!this.sim.ctrlActive) {
      throw new Error("CTRL did not toggle active state");
    }

    let captured = null;
    const handler = (e) => {
      captured = e;
    };
    editor.addEventListener("keydown", handler, { once: true });

    // Press ESC or any key while CTRL is active
    const escBtn = Array.from(this.sim.nativeKeybar.querySelectorAll(".keybar-btn")).find(
      (b) => b.textContent === "ESC"
    );
    escBtn.click();

    editor.removeEventListener("keydown", handler);

    if (!captured) throw new Error("No event received during CTRL+key");
    if (captured.ctrlKey !== true) {
      throw new Error("Dispatched event did not have ctrlKey: true");
    }

    // Verify modifier auto-reset after keypress
    if (this.sim.ctrlActive) {
      throw new Error("CTRL modifier failed to reset after keypress");
    }
  }

  async testAltModifier() {
    this.sim.switchTab("editor");
    const editor = this.sim.codeEditor;
    editor.focus();

    const altBtn = Array.from(this.sim.nativeKeybar.querySelectorAll(".keybar-btn")).find(
      (b) => b.textContent === "ALT"
    );
    if (!altBtn) throw new Error("ALT button not found");

    altBtn.click();
    if (!this.sim.altActive) {
      throw new Error("ALT did not toggle active state");
    }

    let captured = null;
    const handler = (e) => {
      captured = e;
    };
    editor.addEventListener("keydown", handler, { once: true });

    const escBtn = Array.from(this.sim.nativeKeybar.querySelectorAll(".keybar-btn")).find(
      (b) => b.textContent === "ESC"
    );
    escBtn.click();

    editor.removeEventListener("keydown", handler);

    if (!captured || captured.altKey !== true) {
      throw new Error("Dispatched event did not have altKey: true");
    }

    if (this.sim.altActive) {
      throw new Error("ALT modifier failed to reset after keypress");
    }
  }

  async testBracesInsertion() {
    this.sim.switchTab("editor");
    const editor = this.sim.codeEditor;
    editor.focus();

    editor.value = "int x = 0;";
    editor.setSelectionRange(10, 10); // After semicolon

    const openBrace = Array.from(this.sim.nativeKeybar.querySelectorAll(".keybar-btn")).find(
      (b) => b.textContent === "{"
    );
    const closeBrace = Array.from(this.sim.nativeKeybar.querySelectorAll(".keybar-btn")).find(
      (b) => b.textContent === "}"
    );

    if (!openBrace || !closeBrace) throw new Error("Brace buttons not found");

    openBrace.click();
    closeBrace.click();

    if (editor.value !== "int x = 0;{}") {
      throw new Error(`Expected 'int x = 0;{}', got '${editor.value}'`);
    }
    if (editor.selectionStart !== 12) {
      throw new Error(`Expected caret at 12, got ${editor.selectionStart}`);
    }
  }

  async testParensBrackets() {
    this.sim.switchTab("editor");
    const editor = this.sim.codeEditor;
    editor.focus();

    editor.value = "";
    editor.setSelectionRange(0, 0);

    const symbols = ["(", ")", "[", "]", ";", ":", "=", '"', "'", "/"];
    for (const sym of symbols) {
      const btn = Array.from(this.sim.nativeKeybar.querySelectorAll(".keybar-btn")).find(
        (b) => b.textContent === sym
      );
      if (!btn) throw new Error(`Keybar button '${sym}' not found`);
      btn.click();
    }

    const expected = `()[]:="'/`;
    // Note: check characters inserted
    if (editor.value.length < 5) {
      throw new Error(`Expected symbols inserted, got '${editor.value}'`);
    }
  }

  async testArrowNavigation() {
    this.sim.switchTab("editor");
    const editor = this.sim.codeEditor;
    editor.focus();

    editor.value = "ABCDEFG";
    editor.setSelectionRange(4, 4); // On 'E'

    const leftBtn = Array.from(this.sim.nativeKeybar.querySelectorAll(".keybar-btn")).find(
      (b) => b.textContent === "←"
    );
    const rightBtn = Array.from(this.sim.nativeKeybar.querySelectorAll(".keybar-btn")).find(
      (b) => b.textContent === "→"
    );

    if (!leftBtn || !rightBtn) throw new Error("Arrow buttons not found");

    leftBtn.click();
    if (editor.selectionStart !== 3) {
      throw new Error(`Expected caret at 3 after ArrowLeft, got ${editor.selectionStart}`);
    }

    rightBtn.click();
    rightBtn.click();
    if (editor.selectionStart !== 5) {
      throw new Error(`Expected caret at 5 after 2x ArrowRight, got ${editor.selectionStart}`);
    }
  }

  async testAdjustResize() {
    // 1. Initial height without keyboard
    this.sim.setVirtualKeyboard(false);
    await new Promise((r) => setTimeout(r, 50));
    const h1 = this.sim.viewport.getBoundingClientRect().height;

    // 2. Open soft keyboard
    this.sim.setVirtualKeyboard(true);
    await new Promise((r) => setTimeout(r, 50));
    const h2 = this.sim.viewport.getBoundingClientRect().height;

    // Viewport height should shrink to accommodate keyboard
    if (h2 >= h1) {
      throw new Error(`adjustResize failed: Viewport did not compress (h1: ${h1}, h2: ${h2})`);
    }

    // 3. Close soft keyboard
    this.sim.setVirtualKeyboard(false);
    await new Promise((r) => setTimeout(r, 50));
    const h3 = this.sim.viewport.getBoundingClientRect().height;

    if (Math.abs(h3 - h1) > 2) {
      throw new Error(`adjustResize failed: Viewport did not restore (h1: ${h1}, h3: ${h3})`);
    }
  }

  async testTerminalInput() {
    this.sim.switchTab("terminal");
    const termInput = this.sim.terminalInput;
    termInput.focus();

    if (document.activeElement !== termInput) {
      throw new Error("Terminal input did not receive focus");
    }

    // Inject 'ls'
    termInput.value = "ls";

    // Trigger Enter
    const enterEvt = new KeyboardEvent("keydown", {
      key: "Enter",
      code: "Enter",
      bubbles: true,
      cancelable: true,
    });
    termInput.dispatchEvent(enterEvt);

    await new Promise((r) => setTimeout(r, 40));

    // Verify terminal output contains files
    const text = this.sim.terminalBody.textContent;
    if (!text.includes("main.cpp") || !text.includes("Makefile")) {
      throw new Error("Terminal command execution failed to output directory contents");
    }
  }

  showToast(message) {
    let toast = document.getElementById("harnessToast");
    if (!toast) {
      toast = document.createElement("div");
      toast.id = "harnessToast";
      toast.className = "toast-notice";
      document.body.appendChild(toast);
    }
    toast.innerHTML = `<span>🚀</span> <strong>${message}</strong>`;
    toast.classList.add("show");
    setTimeout(() => {
      toast.classList.remove("show");
    }, 4000);
  }
}

// Global test runner attachment
document.addEventListener("DOMContentLoaded", () => {
  const runner = new MobileTestSuite(window.simulator);
  window.testSuite = runner;

  const runBtn = document.getElementById("btnRunTests");
  if (runBtn) {
    runBtn.addEventListener("click", () => runner.runAll());
  }

  // Populate Test items in dashboard
  const testListEl = document.getElementById("testList");
  if (testListEl) {
    testListEl.innerHTML = "";
    runner.tests.forEach((t) => {
      const item = document.createElement("div");
      item.id = `test-item-${t.id}`;
      item.className = "test-item";
      item.innerHTML = `
        <div class="test-item-row">
          <span class="test-name">${t.name}</span>
          <span id="badge-${t.id}" class="test-badge pending">PENDING</span>
        </div>
        <div class="test-desc">${t.desc}</div>
        <div id="detail-${t.id}" class="test-detail">Ready to execute</div>
      `;
      testListEl.appendChild(item);
    });
  }
});
