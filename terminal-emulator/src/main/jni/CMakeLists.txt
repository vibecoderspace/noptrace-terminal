cmake_minimum_required(VERSION 3.22)
project(termux C ASM)

# ---------------------------------------------------------------------------
# Paths
#
# Layout:
#   <repo>/native/noptrace/                  git submodule (noptrace)
#   <repo>/terminal-emulator/src/main/jni/   this file
# ---------------------------------------------------------------------------

set(NOPTRACE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/noptrace)

if(NOT EXISTS ${NOPTRACE_DIR}/src/main.c)
    message(FATAL_ERROR
        "noptrace not found at ${NOPTRACE_DIR}. "
        "Add it as a submodule: git submodule add "
        "https://github.com/vibecoderspace/noptrace native/noptrace")
endif()

# ---------------------------------------------------------------------------
# libtermux.so (JNI)
#
# A normal JNI library: System.loadLibrary("termux") loads it. On arm64-v8a
# it also carries the noptrace engine statically; createSubprocessEngine()
# enters it in a fork()ed child. Nothing is ever exec'd.
# ---------------------------------------------------------------------------

add_library(termux SHARED termux.c)

target_compile_features(termux PRIVATE c_std_11)
target_compile_options(termux PRIVATE -Wall -Wextra)
target_link_libraries(termux log)
set_target_properties(termux PROPERTIES
    C_VISIBILITY_PRESET default
    POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------------------
# noptrace: the in-process AArch64 userland engine
#
# Compiled exactly as its own Makefile builds it (freestanding, no libc),
# minus the standalone-only -static/-no-pie/-T linker script. The NDK default
# -fPIC replaces them; ASLR already keeps libtermux.so clear of the fixed
# guest addresses the standalone linker script existed to dodge.
#
# start.S is excluded: it is the standalone _start, which assumes the kernel
# built the initial stack. noptrace_embed.c reconstructs that contract in the
# fork()ed child and calls cng_main() directly.
# ---------------------------------------------------------------------------

if(ANDROID_ABI STREQUAL "arm64-v8a")
    file(GLOB_RECURSE _noptrace_srcs CONFIGURE_DEPENDS
        ${NOPTRACE_DIR}/src/*.c
        ${NOPTRACE_DIR}/src/*.S)
    list(FILTER _noptrace_srcs EXCLUDE REGEX "src/rt/start\\.S$")

    if(NOT _noptrace_srcs)
        message(FATAL_ERROR "noptrace: no sources under ${NOPTRACE_DIR}/src")
    endif()

    add_library(noptrace STATIC ${_noptrace_srcs})
    target_include_directories(noptrace PRIVATE ${NOPTRACE_DIR}/include)

    # rt.c defines mem/str helpers under their libc names so the standalone
    # -nostdlib link resolves compiler-emitted calls. Inside libtermux.so those
    # definitions would interpose on Bionic and on termux.c, so rename them via
    # the preprocessor. Definitions, rt.h declarations and explicit calls
    # rename together; compiler-emitted memcpy/memset still bind to Bionic,
    # which is safe and faster.
    target_compile_definitions(noptrace PRIVATE
        memset=cng_embed_memset
        memcpy=cng_embed_memcpy
        memmove=cng_embed_memmove
        memcmp=cng_embed_memcmp
        strlen=cng_embed_strlen
        strcmp=cng_embed_strcmp
        strncmp=cng_embed_strncmp
        strchr=cng_embed_strchr
        strrchr=cng_embed_strrchr)

    # -mgeneral-regs-only is load-bearing, not hygiene: on the -R trampoline
    # tier this code runs on the guest's own register file, and the kernel
    # preserves FP registers across a syscall, so any v/d register the compiler
    # spends here is taken from the guest. It applies to .S too (clang turns it
    # into a target feature for its integrated assembler), which is why
    # tramp.S brackets its one FP function in .arch_extension fp/nofp.
    #
    # noptrace_image.h is force-included into every source: it replaces the two
    # symbols the standalone linker script defines with libtermux.so's own
    # image bounds, which the engine checks every guest MAP_FIXED address
    # against.
    target_compile_options(noptrace PRIVATE
        -O2 -std=gnu11 -ffreestanding -fno-builtin -fno-stack-protector
        -fno-asynchronous-unwind-tables -fno-common
        -ffunction-sections -fdata-sections
        -mno-outline-atomics -mgeneral-regs-only
        -Wall -Wextra -Wno-unused-parameter
        "SHELL:-include ${CMAKE_CURRENT_SOURCE_DIR}/noptrace_image.h")

    if(CMAKE_C_COMPILER_ID STREQUAL "GNU")
        # Stop GCC turning the mem* loops into calls to themselves. Clang
        # rejects the flag and honors -ffreestanding instead.
        target_compile_options(noptrace PRIVATE
            -fno-tree-loop-distribute-patterns)
    endif()

    # The syscall table is ours (include/cng/unistd.h); this unit holds it
    # against the build host's <asm/unistd.h>, so a number the two spell
    # differently is a macro redefinition. Never put -w back on this target.
    set_source_files_properties(${NOPTRACE_DIR}/src/rt/unistd_check.c
        PROPERTIES COMPILE_OPTIONS -Werror)

    # The embed shim replaces start.S. HAVE_NOPTRACE gates the JNI branch and
    # the hasNoptrace() answer.
    target_sources(termux PRIVATE noptrace_embed.c)
    target_compile_definitions(termux PRIVATE HAVE_NOPTRACE)
    target_link_libraries(termux noptrace)

    # --exclude-libs hides every engine symbol and, more importantly, makes
    # them non-preemptible. tramp.S's template LDRs PC-relative literals named
    # by global labels; lld refuses that relocation against a visible symbol in
    # a shared object. Hidden => resolved at link time.
    target_link_options(termux PRIVATE -Wl,--exclude-libs,libnoptrace.a)
endif()
