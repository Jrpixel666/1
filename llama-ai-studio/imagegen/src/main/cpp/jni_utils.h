#pragma once

#include <jni.h>
#include <string>

std::string jstringToUtf8(JNIEnv *env, jstring s);
jstring utf8ToJstring(JNIEnv *env, const std::string &s);

// Length of the longest prefix of [s] that does not end mid-way through a UTF-8
// multi-byte sequence (used to hold back half a character until the next token).
size_t utf8CompletePrefixLength(const std::string &s);

#define SD_LOG_TAG "SdBridge"
void sdLogInfo(const char *fmt, ...);
void sdLogError(const char *fmt, ...);
