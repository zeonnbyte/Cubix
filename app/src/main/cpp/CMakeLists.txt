cmake_minimum_required(VERSION 3.22.1)
project("cubix" C CXX ASM)

add_library(cubix SHARED launcher.cpp)

find_library(log-lib log)
target_link_libraries(cubix ${log-lib})

# Pengganti library Minecraft yang dilindungi PairIP (dibuat dari daftar simbol aslinya).
add_library(maesdk SHARED maesdk_stub.S maesdk_core.c)
add_library(PlayFabMultiplayer SHARED playfab_stub.S playfab_core.c)
set_source_files_properties(maesdk_core.c PROPERTIES COMPILE_FLAGS "-marm")
set_target_properties(maesdk PlayFabMultiplayer PROPERTIES LINKER_LANGUAGE C)
