/* Annex K memset_s for libc implementations (glibc, bionic) that lack it. */
#include <string.h>
#include <stddef.h>
int memset_s(void *dest, size_t destsz, int ch, size_t count)
{
	volatile unsigned char *p = dest;
	if (dest == NULL) return 22; /* EINVAL */
	if (count > destsz) count = destsz;
	while (count--) *p++ = (unsigned char) ch;
	return 0;
}
