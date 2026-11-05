/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */
/*
 * Force-included into every noptrace source in place of the two symbols its
 * standalone linker script (scripts/chroot-ng.ld) defines:
 * __cng_image_start/__cng_image_end, the bounds of the monitor's own image.
 *
 * The guest shares the monitor's address space, so the engine checks every
 * guest-chosen MAP_FIXED address against these bounds. Embedded, the monitor's
 * image is libtermux.so, and lld already brackets it: __ehdr_start is the ELF
 * header at its load base, _end the end of its last .bss.
 *
 * Both are declared hidden. lld defines _end with default visibility, so a
 * plain reference in a -shared link exports it and binds the engine's uses
 * through a preemptible GOT slot, which the dynamic linker could fill with
 * another library's _end. A hidden reference keeps it local and unexported,
 * reached by a direct PC-relative adr.
 */
#ifndef NOPTRACE_IMAGE_H
#define NOPTRACE_IMAGE_H

#define __cng_image_start __ehdr_start
#define __cng_image_end _end

#ifndef __ASSEMBLER__
extern char __ehdr_start[] __attribute__((visibility("hidden")));
extern char _end[] __attribute__((visibility("hidden")));
#endif

#endif /* NOPTRACE_IMAGE_H */
