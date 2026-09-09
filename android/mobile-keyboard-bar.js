/**
 * OpenVScode - Mobile Touch Coding Key Bar
 * Injects a floating/docked touch bar above the Android virtual keyboard
 * providing easy access to ESC, TAB, CTRL, ALT, brackets, and arrows.
 */
(function () {
  if (window.__mobileKeyBarLoaded) return;
  window.__mobileKeyBarLoaded = true;

  const bar = document.createElement("div");
  bar.id = "openvscode-mobile-keybar";
  bar.style.cssText = `
    position: fixed;
    bottom: 0;
    left: 0;
    right: 0;
    height: 38px;
    background: #181818;
    border-top: 1px solid #333;
    display: flex;
    overflow-x: auto;
    overflow-y: hidden;
    white-space: nowrap;
    z-index: 999999;
    padding: 3px 6px;
    box-shadow: 0 -2px 8px rgba(0,0,0,0.5);
    user-select: none;
    -webkit-user-select: none;
  `;

  const keys = [
    { label: "ESC", key: "Escape" },
    { label: "TAB", key: "Tab" },
    { label: "CTRL", key: "Control", toggle: true },
    { label: "ALT", key: "Alt", toggle: true },
    { label: "{", char: "{" },
    { label: "}", char: "}" },
    { label: "(", char: "(" },
    { label: ")", char: ")" },
    { label: "[", char: "[" },
    { label: "]", char: "]" },
    { label: ";", char: ";" },
    { label: ":", char: ":" },
    { label: "=", char: "=" },
    { label: "\"", char: "\"" },
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

  let ctrlActive = false;
  let altActive = false;
  let lastActiveElement = null;

  // Track the most recent active element in the IDE
  document.addEventListener("focusin", (e) => {
    if (e.target && !bar.contains(e.target)) {
      lastActiveElement = e.target;
    }
  });

  keys.forEach((k) => {
    const btn = document.createElement("button");
    btn.textContent = k.label;
    btn.style.cssText = `
      background: #2d2d2d;
      color: #e0e0e0;
      border: 1px solid #444;
      border-radius: 4px;
      margin: 0 3px;
      padding: 2px 10px;
      font-family: monospace;
      font-size: 13px;
      font-weight: bold;
      flex-shrink: 0;
      outline: none;
      touch-action: manipulation;
      cursor: pointer;
    `;

    // CRITICAL: Prevent stealing focus from active editor / terminal
    btn.addEventListener("pointerdown", (e) => {
      e.preventDefault();
    });
    btn.addEventListener("mousedown", (e) => {
      e.preventDefault();
    });

    btn.addEventListener("click", (e) => {
      e.preventDefault();
      e.stopPropagation();

      if (k.toggle) {
        if (k.key === "Control") {
          ctrlActive = !ctrlActive;
          btn.style.background = ctrlActive ? "#007acc" : "#2d2d2d";
        } else if (k.key === "Alt") {
          altActive = !altActive;
          btn.style.background = altActive ? "#007acc" : "#2d2d2d";
        }
        return;
      }

      // Resolve the target element
      let target = (document.activeElement && !bar.contains(document.activeElement) && document.activeElement !== document.body)
        ? document.activeElement
        : (lastActiveElement ||
           document.querySelector('.monaco-editor.focused textarea.inputarea') ||
           document.querySelector('.monaco-editor textarea.inputarea') ||
           document.querySelector('.terminal.xterm textarea.xterm-helper-textarea') ||
           document.querySelector('textarea, input:not([type="hidden"])') ||
           document.body);

      // Re-focus target if it somehow blurred
      if (target && typeof target.focus === "function" && document.activeElement !== target) {
        try { target.focus(); } catch (e) {}
      }

      const isModifierCombo = ctrlActive || altActive;

      if (k.char && !isModifierCombo) {
        // Direct character insertion
        if (typeof target.setRangeText === "function") {
          const start = target.selectionStart;
          const end = target.selectionEnd;
          target.setRangeText(k.char, start, end, "end");
          target.dispatchEvent(new InputEvent("input", {
            bubbles: true,
            cancelable: false,
            inputType: "insertText",
            data: k.char
          }));
        } else {
          document.execCommand("insertText", false, k.char);
        }
      } else {
        const keyValue = k.char || k.key;
        const keyMap = {
          "Escape": 27, "Tab": 9, "Control": 17, "Alt": 18,
          "ArrowLeft": 37, "ArrowUp": 38, "ArrowRight": 39, "ArrowDown": 40
        };
        const numericCode = keyMap[k.key] || 0;
        const keyCode = k.key || ("Key" + keyValue.toUpperCase());

        const evtDown = new KeyboardEvent("keydown", {
          key: keyValue,
          code: keyCode,
          keyCode: numericCode,
          which: numericCode,
          ctrlKey: ctrlActive,
          altKey: altActive,
          bubbles: true,
          cancelable: true
        });
        try {
          Object.defineProperty(evtDown, "keyCode", { get: () => numericCode });
          Object.defineProperty(evtDown, "which", { get: () => numericCode });
        } catch (e) {}
        target.dispatchEvent(evtDown);

        // Editor & Terminal navigation helpers
        if (!isModifierCombo) {
          if (k.key === "Tab" && typeof target.setRangeText === "function") {
            const start = target.selectionStart;
            const end = target.selectionEnd;
            target.setRangeText("    ", start, end, "end");
            target.dispatchEvent(new InputEvent("input", {
              bubbles: true,
              cancelable: false,
              inputType: "insertText",
              data: "    "
            }));
          } else if (k.key === "ArrowLeft" && typeof target.setSelectionRange === "function") {
            const p = Math.max(0, target.selectionStart - 1);
            target.setSelectionRange(p, p);
          } else if (k.key === "ArrowRight" && typeof target.setSelectionRange === "function") {
            const p = Math.min((target.value ? target.value.length : 0), target.selectionEnd + 1);
            target.setSelectionRange(p, p);
          }
        }

        const evtUp = new KeyboardEvent("keyup", {
          key: keyValue,
          code: keyCode,
          keyCode: numericCode,
          which: numericCode,
          ctrlKey: ctrlActive,
          altKey: altActive,
          bubbles: true,
          cancelable: true
        });
        try {
          Object.defineProperty(evtUp, "keyCode", { get: () => numericCode });
          Object.defineProperty(evtUp, "which", { get: () => numericCode });
        } catch (e) {}
        target.dispatchEvent(evtUp);
      }

      // Reset modifier keys after non-modifier press
      if (ctrlActive) {
        ctrlActive = false;
        const cBtn = Array.from(bar.children).find((b) => b.textContent === "CTRL");
        if (cBtn) cBtn.style.background = "#2d2d2d";
      }
      if (altActive) {
        altActive = false;
        const aBtn = Array.from(bar.children).find((b) => b.textContent === "ALT");
        if (aBtn) aBtn.style.background = "#2d2d2d";
      }
    });

    bar.appendChild(btn);
  });

  document.body.appendChild(bar);
  console.log("[OpenVScode] Mobile touch key bar mounted.");
})();
