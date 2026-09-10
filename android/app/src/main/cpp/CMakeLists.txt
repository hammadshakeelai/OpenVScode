cmake_minimum_required(VERSION 3.22.1)
project(exechook C)

# The exec-rewriting shim. Ships as a normal .so so Android extracts it into the
# native library directory, which is the one place an app may execute from.
add_library(exechook SHARED exechook.c)

find_library(log-lib log)
target_link_libraries(exechook ${log-lib})

# dlsym(RTLD_NEXT, ...) needs the symbol to be interposable, so keep default
# visibility and do not let the linker resolve our own calls internally.
target_compile_options(exechook PRIVATE -fvisibility=default -Wall -Wextra)
