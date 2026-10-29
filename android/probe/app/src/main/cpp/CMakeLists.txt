cmake_minimum_required(VERSION 3.22)
project(lasprobe C CXX)

set(REPO ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../..)

# adrenotools: loads a custom Vulkan driver (Turnip); its hook libraries are shared libraries packaged with the app
add_subdirectory(${REPO}/third_party/libadrenotools ${CMAKE_BINARY_DIR}/adrenotools)

add_library(lasprobe SHARED probe.c vk_probe.c ${REPO}/android/common/guest_window.c)
target_include_directories(lasprobe PRIVATE ${REPO}/android/common)
target_compile_options(lasprobe PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(lasprobe adrenotools log dl)
