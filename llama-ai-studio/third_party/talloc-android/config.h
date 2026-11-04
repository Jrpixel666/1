/* Minimal hand-written config for building talloc standalone (proot dependency). */
#define HAVE_STDBOOL_H 1
#define HAVE_BOOL 1
#define HAVE_STDINT_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STDIO_H 1
#define HAVE_UNISTD_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_LIMITS_H 1
#define HAVE_STDARG_H 1
#define HAVE_STDDEF_H 1
#define HAVE_ERRNO_H 1
#define HAVE_SYS_AUXV_H 1
#define HAVE_GETAUXVAL 1
#define HAVE_VA_COPY 1
#define HAVE_C99_VSNPRINTF 1
#define HAVE_VSNPRINTF 1
#define HAVE_SNPRINTF 1
#define HAVE_VASPRINTF 1
#define HAVE_ASPRINTF 1
#define HAVE_STRNLEN 1
#define HAVE_STRNDUP 1
#define HAVE_STRDUP 1
#define HAVE_MEMMOVE 1
#define HAVE_INTPTR_T 1
#define HAVE_UINTPTR_T 1
#define HAVE_PTRDIFF_T 1
#define HAVE_SIG_ATOMIC_T_TYPE 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE___ATTRIBUTE__ 1
#define HAVE_VISIBILITY_ATTR 1
#define HAVE_INCOHERENT_MMAP 0
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 4
#define TALLOC_BUILD_VERSION_RELEASE 2
#define _PUBLIC_ __attribute__((visibility("default")))
#define HAVE_USLEEP 1
#define HAVE_DLFCN_H 1
#define HAVE_DLSYM 1
#define HAVE_DLOPEN 1
#define HAVE_GETPEEREID 1
#define HAVE_SETPROCTITLE 1
#define HAVE_MEMSET_S 1
#include <stddef.h>
#include <dlfcn.h>
/* Neither glibc nor bionic ship C11 Annex K memset_s; talloc_compat.c provides it. */
int memset_s(void *dest, size_t destsz, int ch, size_t count);
