Hand-written build configuration for compiling talloc standalone (without its waf build
system) for Android/bionic, used by scripts/build-proot.sh:
- config.h: the HAVE_* answers waf would normally detect.
- talloc_compat.c: memset_s (C11 Annex K), which neither bionic nor glibc provide.
