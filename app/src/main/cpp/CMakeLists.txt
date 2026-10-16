cmake_minimum_required(VERSION 3.22.1)
project("cubix" C CXX ASM)

add_library(cubix SHARED launcher.cpp)
target_compile_definitions(cubix PRIVATE CUBIX_VERSION="${CUBIX_VERSION}")

find_library(log-lib log)
target_link_libraries(cubix ${log-lib})
