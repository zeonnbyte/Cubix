cmake_minimum_required(VERSION 3.22.1)
project("cubix")

add_library(cubix SHARED launcher.cpp)

find_library(log-lib log)
target_link_libraries(cubix ${log-lib})
