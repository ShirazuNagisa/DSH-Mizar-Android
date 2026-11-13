# Build the QuickJS-NG engine and the JNI bridge for the local agent.
#
# This is a *prebuilt* step on purpose: `ndk-build` runs here, the resulting
# `libmizarquickjs.so` files are checked in under `src/main/jniLibs/<abi>/`, and
# the normal Gradle build never needs the NDK. Rebuild with
# `scripts/build-quickjs.ps1`; the engine version is pinned in
# `src/main/cpp/quickjs/` (QuickJS-NG, MIT).

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := mizarquickjs

# The vendored engine (quickjs-ng 0.10.1, the same file set its CMakeLists
# compiles) plus the JNI bridge that owns the context and drains promise jobs.
LOCAL_SRC_FILES := \
    quickjs/quickjs.c \
    quickjs/cutils.c \
    quickjs/libregexp.c \
    quickjs/libunicode.c \
    quickjs/xsum.c \
    jni_bridge.c

LOCAL_C_INCLUDES := $(LOCAL_PATH)/quickjs

# 16 KB page-size devices (Android 15+ / Play requirement): the linker must
# align every LOAD segment to 16 KB, otherwise the library simply cannot load on
# a device whose kernel page size is 16 KB.
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384

# -fvisibility=hidden keeps QuickJS internals out of the dynamic symbol table;
# JNIEXPORT puts the four entry points and JNI_OnLoad back in.
LOCAL_CFLAGS := -O2 -std=c11 -D_GNU_SOURCE -fvisibility=hidden \
    -Wno-unused-parameter -Wno-sign-compare -Wno-implicit-fallthrough

LOCAL_LDLIBS := -llog -lm

include $(BUILD_SHARED_LIBRARY)
