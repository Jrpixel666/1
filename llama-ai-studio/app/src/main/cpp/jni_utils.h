#pragma once

#include <jni.h>
#include <string>

std::string jstringToUtf8(JNIEnv *env, jstring s);
jstring utf8ToJstring(JNIEnv *env, const std::string &s);

#define LLAMA_LOG_TAG "LlamaBridge"
void llamaLogInfo(const char *fmt, ...);
void llamaLogError(const char *fmt, ...);
