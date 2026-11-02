cmake_minimum_required(VERSION 3.4.1)

project(spake2)

if (CURRENT_PLATFORM)
    include(${CMAKE_CURRENT_SOURCE_DIR}/CMakeLists_CurrentPlatform.cmake)
    return ()
endif ()

set(CMAKE_CXX_STANDARD 17)

set(C_FLAGS "-Werror=format -fdata-sections -ffunction-sections -fno-exceptions -fno-rtti -fno-threadsafe-statics")
set(LINKER_FLAGS "-Wl,--hash-style=both")

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    message("Builing Release...")

    set(C_FLAGS "${C_FLAGS} -O2 -fvisibility=hidden -fvisibility-inlines-hidden")
    set(LINKER_FLAGS "${LINKER_FLAGS} -Wl,-exclude-libs,ALL -Wl,--gc-sections")
else()
    message("Builing Debug...")

    add_definitions(-DDEBUG)
endif ()

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${C_FLAGS}")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${C_FLAGS}")

set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} ${LINKER_FLAGS}")
set(CMAKE_MODULE_LINKER_FLAGS "${CMAKE_MODULE_LINKER_FLAGS} ${LINKER_FLAGS}")

add_library(spake2 SHARED
        spake2-c/sha512.c
        spake2-c/spake2.c
        spake2_jni.cpp)

target_include_directories(spake2 PUBLIC spake2-c/include)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_custom_command(TARGET spake2 POST_BUILD
            COMMAND ${CMAKE_STRIP} --remove-section=.comment "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libspake2.so")
endif ()
