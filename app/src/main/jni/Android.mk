LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := presenterpty
LOCAL_SRC_FILES := pty.c
LOCAL_CFLAGS := -Wall -Wextra -Werror -O2
include $(BUILD_SHARED_LIBRARY)
