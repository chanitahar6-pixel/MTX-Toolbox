# ndk-build application settings. Gradle overrides APP_ABI and APP_PLATFORM from
# the android {} block, these values are the standalone-build defaults.

APP_STL := c++_shared
APP_ABI := arm64-v8a armeabi-v7a x86_64
APP_PLATFORM := android-21
APP_CPPFLAGS := -std=c++17 -frtti -fexceptions
APP_OPTIM := release
