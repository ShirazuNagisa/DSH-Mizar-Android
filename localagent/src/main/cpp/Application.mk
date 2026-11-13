# ndk-build settings for the local agent's QuickJS-NG engine.
#
# x86_64 is the emulator (mizar36); arm64-v8a is every current phone. 32-bit
# ABIs are deliberately absent: minSdk 26 and Play's 64-bit requirement mean
# there is nothing left to run them on, and each one doubles the build and the
# APK's native payload.
APP_ABI := x86_64 arm64-v8a
APP_PLATFORM := android-26
APP_OPTIM := release
APP_STL := none
APP_CFLAGS := -O2 -D_GNU_SOURCE
APP_LDFLAGS := -Wl,-z,max-page-size=16384
