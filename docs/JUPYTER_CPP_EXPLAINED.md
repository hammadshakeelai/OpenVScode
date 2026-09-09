# C++ in Jupyter Notebooks on Mobile (Deep Dive)

Jupyter Notebooks are typically associated with Python, but interactive notebooks for compiled languages like C++ are extremely powerful for learning, prototyping, algorithm testing, and data structures.

---

## 1. How C++ Works in Jupyter

There are two primary architectures for C++ in Jupyter:

### Approach A: `jupyter-cpp-kernel` (Default in OpenVScode - Just as easy as Python)
* **Installation**: A simple `pip install jupyter-cpp-kernel`.
* **Mechanism**: When you run a cell, the kernel extracts the C++ code, wraps it with necessary boilerplate if needed, compiles it into a temporary binary via `clang++` or `g++`, executes the binary, captures `stdout` / `stderr`, and displays the output in the notebook cell.
* **Why it's best for mobile**:
  - Requires **zero Conda environments**.
  - Requires **no complex LLVM JIT compilation**.
  - Works out-of-the-box on ARM64 Linux / Android with standard `clang`.

### Approach B: `xeus-cpp` (Interactive Clang-REPL Kernel)
* **Installation**: Installed via `conda-forge` using `mamba` or `micromamba`:
  ```bash
  mamba create -n xeus_env -c conda-forge xeus-cpp jupyterlab
  ```
* **Mechanism**: Powered by `clang-repl` (introduced in modern LLVM). It actually interprets C++ statements dynamically without re-compiling the entire source file. Variables and functions defined in cell 1 remain in memory for cell 2!
* **Trade-off on mobile**:
  - Package size is ~400MB - 600MB due to full LLVM/Clang JIT shared libraries.
  - Requires a full Debian/Ubuntu PRoot container on Android (`proot-distro install ubuntu`).

---

## 2. Writing C++ Notebook Cells

When using the `C++ (Clang++)` kernel, write complete standard C++ functions:

```cpp
#include <iostream>
#include <vector>
#include <numeric>

int main() {
    std::vector<int> nums = {1, 2, 3, 4, 5};
    int sum = std::accumulate(nums.begin(), nums.end(), 0);
    std::cout << "Sum calculated on phone: " << sum << std::endl;
    return 0;
}
```

### Compiler Arguments & Includes
You can pass custom compiler flags by placing them at the top of the cell:
```cpp
//%flags -std=c++20 -O3
#include <iostream>

int main() {
    std::cout << "Optimized C++20 on ARM64" << std::endl;
    return 0;
}
```

---

## 3. Switching Between Python and C++

In VS Code:
1. Open any `.ipynb` file.
2. In the top-right corner of the notebook editor, tap **Kernel** (or `Select Kernel`).
3. Choose:
   - **Python 3** (for Python notebooks)
   - **C++ (Clang++)** (for C++ notebooks)
4. Tap the **Play** button on any cell to execute!
