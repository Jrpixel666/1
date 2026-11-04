#include "jni_utils.h"

#include <android/log.h>
#include <cstdarg>

std::string jstringToUtf8(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(s, chars);
    return result;
}

jstring utf8ToJstring(JNIEnv *env, const std::string &s) {
    return env->NewStringUTF(s.c_str());
}

static void logv(int prio, const char *fmt, va_list args) {
    __android_log_vprint(prio, SD_LOG_TAG, fmt, args);
}

void sdLogInfo(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    logv(ANDROID_LOG_INFO, fmt, args);
    va_end(args);
}

void sdLogError(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    logv(ANDROID_LOG_ERROR, fmt, args);
    va_end(args);
}
