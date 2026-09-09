#include <iostream>
#include <vector>
#include <numeric>
#include <string>

// Modern C++20 Sample for OpenVScode Mobile
int main() {
    std::cout << "==================================================" << std::endl;
    std::cout << "  Hello from OpenVScode Mobile C++ (Clang)!" << std::endl;
    std::cout << "==================================================" << std::endl;

    std::vector<int> numbers = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    int sum = std::accumulate(numbers.begin(), numbers.end(), 0);

    std::cout << "Vector elements: ";
    for (const auto& n : numbers) {
        std::cout << n << " ";
    }
    std::cout << std::endl;
    std::cout << "Sum of numbers : " << sum << std::endl;
    std::cout << "C++ toolchain and Clang compiler verified!" << std::endl;

    return 0;
}
