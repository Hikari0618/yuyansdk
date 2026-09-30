# 语燕输入法引擎桥接库
#
# 产出:
#   libyuyanime.so       JNI 库（Android / 亦可在 Linux 构建以便测试）
#   yuyan_test           Linux 验证工具（仅非 Android 构建）

find_package(Opencc REQUIRED)

add_library(yuyan-bridge STATIC yuyan_bridge.cc)
target_include_directories(yuyan-bridge PUBLIC
  "${CMAKE_SOURCE_DIR}/librime/src"
  "${CMAKE_SOURCE_DIR}/librime/include"
  "${CMAKE_BINARY_DIR}/librime/src"
  "${Opencc_INCLUDE_PATH}"
)
target_link_libraries(yuyan-bridge rime-static ${Opencc_LIBRARY})

if(ANDROID)
  add_library(yuyanime SHARED yuyan_jni.cc)
  target_link_libraries(yuyanime yuyan-bridge rime-static ${Opencc_LIBRARY})
else()
  add_executable(yuyan_test yuyan_test.cc)
  target_link_libraries(yuyan_test yuyan-bridge rime-static ${Opencc_LIBRARY})
endif()
