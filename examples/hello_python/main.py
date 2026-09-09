#!/usr/bin/env python3
"""
OpenVScode - Python Sample Script
"""
import sys
import platform

def main():
    print("=" * 50)
    print("  Hello from OpenVScode Mobile Python!")
    print("=" * 50)
    print(f"Python Version : {sys.version.split()[0]}")
    print(f"Platform       : {platform.platform()}")
    print(f"Architecture   : {platform.machine()}")
    print("=" * 50)

    # Test basic math and list comprehensions
    squares = [x**2 for x in range(1, 11)]
    print(f"First 10 squares: {squares}")
    print("Python environment is verified and operational!")

if __name__ == "__main__":
    main()
