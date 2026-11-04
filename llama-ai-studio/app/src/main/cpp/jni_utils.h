#pragma once

#include <jni.h>
#include <string>

std::string jstringToUtf8(JNIEnv *env, jstring s);
jstring utf8ToJstring(JNIEnv *env, const std::string &s);

// Length of the longest prefix of [s] that does not end mid-way through a UTF-8
// multi-byte sequence (used to hold back half a character until the next token).
size_t utf8CompletePrefixLength(const std::string &s);

#define LLAMA_LOG_TAG "LlamaBridge"
void llamaLogInfo(const char *fmt, ...);
void llamaLogError(const char *fmt, ...);
