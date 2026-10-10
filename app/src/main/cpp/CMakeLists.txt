cmake_minimum_required(VERSION 3.22)
project(highspeediem LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(OBOE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/oboe")
if(NOT EXISTS "${OBOE_DIR}/CMakeLists.txt")
  message(FATAL_ERROR "Oboe sources missing from app/src/main/cpp/oboe")
endif()
set(OBOE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(${OBOE_DIR} oboe)

add_library(highspeediem SHARED engine.cpp)
target_compile_options(highspeediem PRIVATE -Wall -Wextra -O2)
target_link_libraries(highspeediem oboe android log)
