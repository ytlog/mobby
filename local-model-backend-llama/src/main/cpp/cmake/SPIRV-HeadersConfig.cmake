# Android NDK includes SPIR-V headers but omits their CMake package metadata.
set(_mobby_spirv_include "${CMAKE_ANDROID_NDK}/sources/third_party/shaderc/third_party/spirv-tools/external/spirv-headers/include")
if(NOT EXISTS "${_mobby_spirv_include}/spirv/unified1/spirv.hpp")
    message(FATAL_ERROR "Android NDK SPIR-V headers are unavailable")
endif()
if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED GLOBAL)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES "${_mobby_spirv_include}")
endif()
set(SPIRV-Headers_FOUND TRUE)
