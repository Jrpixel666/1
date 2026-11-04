#include "jni_utils.h"

#include <android/log.h>
#include <cstdarg>
#include <cstdint>
#include <vector>

// JNI's *StringUTF* functions speak "modified UTF-8" (CESU-8 surrogate pairs, 0xC0 0x80 for
// NUL), not the standard UTF-8 that llama.cpp / stable-diffusion.cpp produce and expect.
// Passing standard UTF-8 through them mangles emoji/CJK on the way in, and on the way out
// CheckJNI (on by default in debug builds) aborts the whole process on any byte sequence it
// considers invalid - e.g. a multi-byte character split across two generated tokens. These
// helpers go through UTF-16 (GetStringChars/NewString) instead, which is always well-defined.

std::string jstringToUtf8(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const jsize len = env->GetStringLength(s);
    const jchar *chars = env->GetStringChars(s, nullptr);
    if (chars == nullptr) return {};
    std::string out;
    out.reserve((size_t) len * 3);
    for (jsize i = 0; i < len; i++) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
            i++;
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD; // lone surrogate
        }
        if (cp < 0x80) {
            out += (char) cp;
        } else if (cp < 0x800) {
            out += (char) (0xC0 | (cp >> 6));
            out += (char) (0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += (char) (0xE0 | (cp >> 12));
            out += (char) (0x80 | ((cp >> 6) & 0x3F));
            out += (char) (0x80 | (cp & 0x3F));
        } else {
            out += (char) (0xF0 | (cp >> 18));
            out += (char) (0x80 | ((cp >> 12) & 0x3F));
            out += (char) (0x80 | ((cp >> 6) & 0x3F));
            out += (char) (0x80 | (cp & 0x3F));
        }
    }
    env->ReleaseStringChars(s, chars);
    return out;
}

jstring utf8ToJstring(JNIEnv *env, const std::string &s) {
    std::vector<jchar> out;
    out.reserve(s.size());
    const auto *p = reinterpret_cast<const unsigned char *>(s.data());
    const size_t n = s.size();
    size_t i = 0;
    while (i < n) {
        uint32_t cp;
        size_t extra;
        const unsigned char c = p[i];
        if (c < 0x80) { cp = c; extra = 0; }
        else if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; extra = 1; }
        else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; extra = 2; }
        else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; extra = 3; }
        else { out.push_back(0xFFFD); i++; continue; }
        bool valid = true;
        for (size_t k = 1; k <= extra; k++) {
            if (i + k >= n || (p[i + k] & 0xC0) != 0x80) { valid = false; break; }
            cp = (cp << 6) | (p[i + k] & 0x3F);
        }
        if (!valid) { out.push_back(0xFFFD); i++; continue; }
        i += extra + 1;
        if (cp >= 0x10000 && cp <= 0x10FFFF) {
            cp -= 0x10000;
            out.push_back((jchar) (0xD800 + (cp >> 10)));
            out.push_back((jchar) (0xDC00 + (cp & 0x3FF)));
        } else if (cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
            out.push_back(0xFFFD);
        } else {
            out.push_back((jchar) cp);
        }
    }
    return env->NewString(out.data(), (jsize) out.size());
}

size_t utf8CompletePrefixLength(const std::string &s) {
    // Walk back over at most 3 trailing continuation bytes to find the last lead byte, and
    // check whether the sequence it starts is complete.
    const size_t n = s.size();
    if (n == 0) return 0;
    size_t i = n;
    size_t back = 0;
    while (i > 0 && back < 4) {
        i--;
        back++;
        const unsigned char c = (unsigned char) s[i];
        if ((c & 0xC0) != 0x80) {
            size_t need;
            if (c < 0x80) need = 1;
            else if ((c & 0xE0) == 0xC0) need = 2;
            else if ((c & 0xF0) == 0xE0) need = 3;
            else if ((c & 0xF8) == 0xF0) need = 4;
            else return n; // invalid lead byte: let the decoder replace it
            return back >= need ? n : i;
        }
    }
    return n; // only continuation bytes: invalid, let the decoder replace them
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
