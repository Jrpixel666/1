#pragma once

#include <jni.h>
#include <string>

std::string jstringToUtf8(JNIEnv *env, jstring s);
jstring utf8ToJstring(JNIEnv *env, const std::string &s);

#define SD_LOG_TAG "SdBridge"
void sdLogInfo(const char *fmt, ...);
void sdLogError(const char *fmt, ...);
