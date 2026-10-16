cmake_minimum_required(VERSION 3.22.1)
project("relay" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(relay SHARED
        RelayEngine.cpp
        RelayCallbackMachine.cpp
        RelayEventDispatcher.cpp
        jni_bridge.cpp)

if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_sources(relay PRIVATE RelayCallbackMachineTest.cpp)
endif()

# Newer-than-minSdk AAudio symbols become weak references so the
# __builtin_available guards work instead of failing the build.
target_compile_definitions(relay PRIVATE __ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__)

find_library(log-lib log)

target_link_libraries(relay
        aaudio
        ${log-lib})
