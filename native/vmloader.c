/*
 * vmloader - OpenVM guest userland runner (stage 1).
 *
 * Runs a program from an extracted Android/Linux root filesystem as if the rootfs were "/",
 * without root: ptrace intercepts path-taking syscalls and rewrites them (proot-style).
 * Also makes dynamic guest binaries start: the kernel would look for PT_INTERP (e.g. /system/bin/linker64)
 * on the HOST, so execve is rewritten to "<guest linker> <program> args..." and #! scripts are expanded.
 *
 * usage: vmloader -r ROOTFS [-b HOST:GUEST]... [-w GUESTCWD] [-x \"BGPROG ARGS\"]... [-N] [-v] [-s] [-d] [-0] -- PROGRAM [ARGS...]
 * Builds for aarch64 and x86_64 (static, no libc dependencies beyond musl).
 */
#define _GNU_SOURCE
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>
#include <stdarg.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/prctl.h>
#include <sys/mman.h>
#include <sched.h>

#if defined(__x86_64__)
#include <sys/user.h>
typedef struct user_regs_struct regs_t;
#define R_NR(r)  ((r)->orig_rax)
#define R_RET(r) ((r)->rax)
#define R_SP(r)  ((r)->rsp)
static unsigned long *ARG(regs_t *r, int i) {
    switch (i) { case 0: return &r->rdi; case 1: return &r->rsi; case 2: return &r->rdx;
                 case 3: return &r->r10; case 4: return &r->r8; default: return &r->r9; }
}
#define RET_IS_ARG0 0
#define R_PC(r) ((r)->rip)
#define R_LR(r) 0UL
#define R_FP(r) ((r)->rbp)
#elif defined(__aarch64__)
typedef struct { unsigned long long regs[31], sp, pc, pstate; } regs_t;
#define R_NR(r)  ((r)->regs[8])
#define R_RET(r) ((r)->regs[0])
#define R_SP(r)  ((r)->sp)
static unsigned long long *ARG(regs_t *r, int i) { return &r->regs[i]; }
#define RET_IS_ARG0 1
#define R_PC(r) ((r)->pc)
#define R_LR(r) ((r)->regs[30])
#define R_FP(r) ((r)->regs[29])
#else
#error unsupported architecture
#endif

#define MAXP 1024
#define NPATHS 2

static char ROOT[PATH_MAX]; static size_t ROOTLEN;
static struct { char host[PATH_MAX], guest[PATH_MAX]; } BIND[16]; static int NBIND;
static char GCWD[PATH_MAX];
static char LOGSOCK[sizeof(((struct sockaddr_un *)0)->sun_path)]; static pid_t LOGGER;
static int VERBOSE, TRACE, DIAG, FAKEROOT, NOCRASHDUMP = 1; static unsigned HOSTUID;

static void vlog(const char *f, ...) {
    if (!VERBOSE) return;
    char b[512]; va_list ap; va_start(ap, f);
    vsnprintf(b, sizeof b, f, ap); va_end(ap);
    dprintf(2, "[vmloader] %s\n", b);
}

/* ------------------------------------------------------------------ path translation */
static int has_prefix(const char *p, const char *pre) { size_t n = strlen(pre); return !strncmp(p, pre, n) && (p[n] == 0 || p[n] == '/'); }
/* /dev/__properties__ is the guest's own property area (written by the app into <rootfs>/dev/__properties__), not the host's.
 * /sys/fs/selinux is the guest's own (fake, permissive) selinuxfs, see setup_selinuxfs(): an app is not allowed to read the phone's. */
static int passthrough(const char *g) { return has_prefix(g, "/proc") || (has_prefix(g, "/dev") && !has_prefix(g, "/dev/__properties__") && !has_prefix(g, "/dev/openvm"))
                                              || (has_prefix(g, "/sys") && !has_prefix(g, "/sys/fs/selinux")); }

static void pop_comp(char *g) { char *s = strrchr(g, '/'); if (s == g) g[1] = 0; else if (s) *s = 0; }

/* Resolve absolute GUEST path -> canonical guest path (symlinks followed inside the rootfs). */
static void canon(const char *gpath, int follow_last, char *gout) {
    char rest[PATH_MAX * 2]; char cur[PATH_MAX] = "/"; int loops = 0;
    snprintf(rest, sizeof rest, "%s", gpath);
    while (*rest) {
        char *p = rest; while (*p == '/') p++;
        if (!*p) break;
        char *e = strchr(p, '/'); char comp[NAME_MAX + 1]; size_t cl = e ? (size_t)(e - p) : strlen(p);
        if (cl > NAME_MAX) cl = NAME_MAX;
        memcpy(comp, p, cl); comp[cl] = 0;
        char tail[PATH_MAX * 2]; snprintf(tail, sizeof tail, "%s", e ? e : "");
        int more = 0; for (char *t = tail; *t; t++) if (*t != '/') { more = 1; break; }
        if (!strcmp(comp, ".")) { snprintf(rest, sizeof rest, "%s", tail); continue; }
        if (!strcmp(comp, "..")) { pop_comp(cur); snprintf(rest, sizeof rest, "%s", tail); continue; }
        char cand[PATH_MAX]; snprintf(cand, sizeof cand, "%s%s%s", cur, strcmp(cur, "/") ? "/" : "", comp);
        if (passthrough(cand)) { snprintf(cur, sizeof cur, "%s", cand); snprintf(rest, sizeof rest, "%s", tail); continue; }
        char host[PATH_MAX * 2]; snprintf(host, sizeof host, "%s%s", ROOT, cand);
        struct stat st;
        if ((more || follow_last) && lstat(host, &st) == 0 && S_ISLNK(st.st_mode) && loops++ < 40) {
            char tgt[PATH_MAX]; ssize_t n = readlink(host, tgt, sizeof tgt - 1);
            if (n > 0) {
                tgt[n] = 0;
                if (tgt[0] == '/') snprintf(cur, sizeof cur, "/");
                snprintf(rest, sizeof rest, "%s%s", tgt, tail);
                continue;
            }
        }
        snprintf(cur, sizeof cur, "%s", cand); snprintf(rest, sizeof rest, "%s", tail);
    }
    snprintf(gout, PATH_MAX, "%s", cur);
}

/* Guest absolute path -> host path to hand to the kernel. Returns 1 if changed. */
static int to_host(const char *gpath, int follow_last, char *hout) {
    char g[PATH_MAX];
    if (passthrough(gpath)) { snprintf(hout, PATH_MAX, "%s", gpath); return 0; }
    canon(gpath, follow_last, g);
    if (passthrough(g)) { snprintf(hout, PATH_MAX, "%s", g); return 1; }
    for (int i = 0; i < NBIND; i++) if (has_prefix(g, BIND[i].guest)) {
        snprintf(hout, PATH_MAX, "%s%s", BIND[i].host, g + strlen(BIND[i].guest)); return 1; }
    snprintf(hout, PATH_MAX, "%s%s", ROOT, strcmp(g, "/") ? g : ""); if (!hout[0]) snprintf(hout, PATH_MAX, "/");
    return 1;
}

/* ------------------------------------------------------------------ tracee memory */
static int rd(pid_t pid, unsigned long addr, void *buf, size_t n) {
    struct iovec l = { buf, n }, r = { (void *)addr, n };
    ssize_t k = process_vm_readv(pid, &l, 1, &r, 1, 0);
    if (k == (ssize_t)n) return 0;
    char p[64]; snprintf(p, sizeof p, "/proc/%d/mem", pid);
    int fd = open(p, O_RDONLY); if (fd < 0) return -1;
    k = pread(fd, buf, n, addr); close(fd); return k == (ssize_t)n ? 0 : -1;
}
static int rd_str(pid_t pid, unsigned long addr, char *buf, size_t max) {
    size_t i = 0;
    while (i < max - 1) {
        char c[64]; size_t want = sizeof c - (addr + i) % sizeof c;
        if (rd(pid, addr + i, c, want) != 0) { if (rd(pid, addr + i, c, 1) != 0) return -1; want = 1; }
        for (size_t k = 0; k < want && i < max - 1; k++) { buf[i++] = c[k]; if (!c[k]) return 0; }
    }
    buf[max - 1] = 0; return 0;
}
static int wr(pid_t pid, unsigned long addr, const void *buf, size_t n) {
    struct iovec l = { (void *)buf, n }, r = { (void *)addr, n };
    if (process_vm_writev(pid, &l, 1, &r, 1, 0) == (ssize_t)n) return 0;
    char p[64]; snprintf(p, sizeof p, "/proc/%d/mem", pid);
    int fd = open(p, O_WRONLY); if (fd < 0) return -1;
    ssize_t k = pwrite(fd, buf, n, addr); close(fd); return k == (ssize_t)n ? 0 : -1;
}
static int get_regs(pid_t pid, regs_t *r) { struct iovec v = { r, sizeof *r }; return ptrace(PTRACE_GETREGSET, pid, (void *)NT_PRSTATUS, &v); }
static int set_regs(pid_t pid, regs_t *r) { struct iovec v = { r, sizeof *r }; return ptrace(PTRACE_SETREGSET, pid, (void *)NT_PRSTATUS, &v); }

/* ------------------------------------------------------------------ syscall table */
/* path arg index (-1 = none); flagsarg = which arg holds AT_ flags / open flags, kind 'o' open flags, 'a' AT_SYMLINK_NOFOLLOW, 'n' never follow last, 0 follow */
struct sc { long nr; int a0, a1; char kind; int flagarg; };
static const struct sc TAB[] = {
    { SYS_openat, 1, -1, 'o', 2 }, { SYS_faccessat, 1, -1, 0, 0 }, { SYS_newfstatat, 1, -1, 'a', 3 },
    { SYS_readlinkat, 1, -1, 'n', 0 }, { SYS_mkdirat, 1, -1, 'n', 0 }, { SYS_unlinkat, 1, -1, 'n', 0 },
    { SYS_mknodat, 1, -1, 'n', 0 }, { SYS_fchmodat, 1, -1, 0, 0 }, { SYS_fchownat, 1, -1, 'a', 4 },
    { SYS_utimensat, 1, -1, 'a', 3 }, { SYS_symlinkat, 2, -1, 'n', 0 }, { SYS_linkat, 1, 3, 'n', 0 },
    { SYS_renameat, 1, 3, 'n', 0 }, { SYS_renameat2, 1, 3, 'n', 0 }, { SYS_statx, 1, -1, 'a', 2 },
    { SYS_chdir, 0, -1, 0, 0 }, { SYS_statfs, 0, -1, 0, 0 }, { SYS_truncate, 0, -1, 0, 0 },
    { SYS_chroot, 0, -1, 0, 0 },
#ifdef SYS_faccessat2
    { SYS_faccessat2, 1, -1, 0, 0 },
#endif
#ifdef SYS_open
    { SYS_open, 0, -1, 'o', 1 }, { SYS_stat, 0, -1, 0, 0 }, { SYS_lstat, 0, -1, 'n', 0 }, { SYS_access, 0, -1, 0, 0 },
    { SYS_readlink, 0, -1, 'n', 0 }, { SYS_mkdir, 0, -1, 'n', 0 }, { SYS_rmdir, 0, -1, 'n', 0 }, { SYS_unlink, 0, -1, 'n', 0 },
    { SYS_rename, 0, 1, 'n', 0 }, { SYS_symlink, 1, -1, 'n', 0 }, { SYS_link, 0, 1, 'n', 0 }, { SYS_chmod, 0, -1, 0, 0 },
    { SYS_chown, 0, -1, 0, 0 }, { SYS_lchown, 0, -1, 'n', 0 }, { SYS_creat, 0, -1, 0, 0 }, { SYS_mknod, 0, -1, 'n', 0 },
    { SYS_utimes, 0, -1, 0, 0 }, { SYS_utime, 0, -1, 0, 0 },
#endif
    { 0, 0, 0, 0, 0 }
};
static const struct sc *find_sc(long nr) { for (const struct sc *s = TAB; s->nr; s++) if (s->nr == nr) return s; return NULL; }

struct bfd;
struct tp { pid_t pid; int in_sys; long nr; unsigned long saved[6]; unsigned long touched; char path[160]; int skip; int skiperr;
  pid_t tgid; int binjected, bemu, deferred, bopen_dev, bopen_sup, bclose; long bret; char bopen_path[PATH_MAX]; struct bfd *bmmap; unsigned long bmmap_len;
  char selpath[200]; };   /* selpath: per-open scratch file made for a /sys/fs/selinux request file, deleted when the open returns */
static struct tp T[MAXP];
static struct tp *tget(pid_t pid, int create) {
    for (int i = 0; i < MAXP; i++) if (T[i].pid == pid) return &T[i];
    if (!create) return NULL;
    for (int i = 0; i < MAXP; i++) if (!T[i].pid) { memset(&T[i], 0, sizeof T[i]); T[i].pid = pid; return &T[i]; }
    return NULL;
}

/* ------------------------------------------------------------------ exec planning (linker / shebang) */
static int read_head(const char *host, unsigned char *buf, size_t n) {
    int fd = open(host, O_RDONLY); if (fd < 0) return -1;
    ssize_t k = read(fd, buf, n); close(fd); return (int)k;
}
static int elf_interp(const char *host, char *interp, size_t max) {
    int fd = open(host, O_RDONLY); if (fd < 0) return 0;
    Elf64_Ehdr eh; int ok = 0;
    if (read(fd, &eh, sizeof eh) == sizeof eh && !memcmp(eh.e_ident, ELFMAG, SELFMAG) && eh.e_ident[EI_CLASS] == ELFCLASS64) {
        for (int i = 0; i < eh.e_phnum && !ok; i++) {
            Elf64_Phdr ph;
            if (pread(fd, &ph, sizeof ph, eh.e_phoff + (off_t)i * eh.e_phentsize) != sizeof ph) break;
            if (ph.p_type == PT_INTERP && ph.p_filesz < max) { if (pread(fd, interp, ph.p_filesz, ph.p_offset) == (ssize_t)ph.p_filesz) { interp[ph.p_filesz] = 0; ok = 1; } }
        }
    }
    close(fd); return ok;
}

/* Rewrite an execve at syscall entry. regs hold the original args. Returns 1 if args were changed. */
static int rewrite_exec(pid_t pid, regs_t *r, struct tp *t) {
    char gpath[PATH_MAX];
    if (rd_str(pid, *ARG(r, 0), gpath, sizeof gpath)) return 0;
    if (gpath[0] != '/') { char tmp[PATH_MAX]; snprintf(tmp, sizeof tmp, "%s/%s", GCWD, gpath); snprintf(gpath, sizeof gpath, "%s", tmp); }
    char P[6][PATH_MAX]; int np = 0;
    char cur[PATH_MAX]; snprintf(cur, sizeof cur, "%s", gpath);
    char hostexec[PATH_MAX];
    for (int depth = 0; depth < 6; depth++) {
        char host[PATH_MAX]; to_host(cur, 1, host);
        snprintf(hostexec, sizeof hostexec, "%s", host);
        unsigned char head[256]; int n = read_head(host, head, sizeof head - 1);
        if (n > 2 && head[0] == '#' && head[1] == '!') {
            head[n] = 0; char *l = (char *)head + 2; char *nl = strchr(l, '\n'); if (nl) *nl = 0;
            while (*l == ' ' || *l == '\t') l++;
            char *sp = l; while (*sp && *sp != ' ' && *sp != '\t') sp++;
            char *arg = NULL; if (*sp) { *sp++ = 0; while (*sp == ' ' || *sp == '\t') sp++; if (*sp) arg = sp; }
            if (np + 2 > 6) return 0;
            for (int i = np - 1; i >= 0; i--) snprintf(P[i + (arg ? 2 : 1)], PATH_MAX, "%s", P[i]);
            snprintf(P[0], PATH_MAX, "%s", l); if (arg) snprintf(P[1], PATH_MAX, "%s", arg);
            np += arg ? 2 : 1; snprintf(cur, sizeof cur, "%s", l); continue;
        }
        char ip[PATH_MAX];
        if (elf_interp(host, ip, sizeof ip)) {
            if (np + 1 > 6) return 0;
            for (int i = np - 1; i >= 0; i--) snprintf(P[i + 1], PATH_MAX, "%s", P[i]);
            snprintf(P[0], PATH_MAX, "%s", ip); np++;
            to_host(ip, 1, hostexec);
        }
        break;
    }
    if (np == 0) {                               /* plain static program: only the file path changes, argv stays as the program expects */
        size_t hl0 = strlen(hostexec) + 1; unsigned long hp = ((R_SP(r) - 256 - 8192) & ~15UL);
        if (wr(pid, hp, hostexec, hl0)) return 0;
        *ARG(r, 0) = hp; t->touched |= 1; vlog("exec %s -> %s", gpath, hostexec); return 1;
    }
    /* original argv pointers */
    unsigned long argv = *ARG(r, 1); unsigned long orig[256]; int na = 0;
    for (; na < 255; na++) { unsigned long p = 0; if (!argv || rd(pid, argv + na * 8, &p, 8) || !p) break; orig[na] = p; }
    /* scratch area below the stack (skip the x86-64 red zone) */
    unsigned long base = (R_SP(r) - 256 - 24576) & ~15UL;
    unsigned long off = 0;
    unsigned long hostptr = base + off; size_t hl = strlen(hostexec) + 1; if (wr(pid, hostptr, hostexec, hl)) return 0; off += (hl + 15) & ~15UL;
    unsigned long strp[6];
    for (int i = 0; i < np; i++) { size_t l = strlen(P[i]) + 1; strp[i] = base + off; if (wr(pid, strp[i], P[i], l)) return 0; off += (l + 15) & ~15UL; }
    unsigned long nv[300]; int nn = 0;
    for (int i = 0; i < np; i++) nv[nn++] = strp[i];
    nv[nn++] = *ARG(r, 0);                       /* program as invoked (guest path string, still in tracee memory) */
    for (int i = 1; i < na; i++) nv[nn++] = orig[i];
    nv[nn++] = 0;
    unsigned long argvp = base + off; if (wr(pid, argvp, nv, nn * 8)) return 0;
    *ARG(r, 0) = hostptr; *ARG(r, 1) = argvp; t->touched |= 3;
    vlog("exec %s -> %s (+%d args)", gpath, hostexec, np);
    return 1;
}

/* ------------------------------------------------------------------ trace helper */
static const char *sname(long nr) {
    switch (nr) {
#ifdef __aarch64__
    case 19: return "eventfd2"; case 20: return "epoll_create1"; case 29: return "ioctl"; case 48: return "faccessat"; case 56: return "openat";
    case 57: return "close"; case 59: return "pipe2"; case 63: return "read"; case 64: return "write"; case 67: return "pread64"; case 78: return "readlinkat";
    case 79: return "newfstatat"; case 80: return "fstat"; case 98: return "futex"; case 122: return "sched_setaffinity"; case 123: return "sched_getaffinity";
    case 131: return "tgkill"; case 132: return "sigaltstack"; case 134: return "rt_sigaction"; case 135: return "rt_sigprocmask"; case 140: return "setpriority";
    case 163: return "getrlimit"; case 164: return "setrlimit"; case 167: return "prctl"; case 179: return "sysinfo"; case 198: return "socket"; case 203: return "connect";
    case 215: return "munmap"; case 216: return "mremap"; case 220: return "clone"; case 221: return "execve"; case 222: return "mmap"; case 226: return "mprotect";
    case 228: return "mlock"; case 232: return "mincore"; case 233: return "madvise"; case 261: return "prlimit64"; case 270: return "process_vm_readv";
    case 278: return "getrandom"; case 279: return "memfd_create"; case 282: return "userfaultfd"; case 283: return "membarrier"; case 291: return "statx";
    case 436: return "close_range"; case 439: return "faccessat2"; case 435: return "clone3";
#endif
    default: return "sys";
    }
}


/* ------------------------------------------------------------------ virtual root (-0)
 * The guest believes it is uid/gid 0. This is only a lie told to guest programs: the host kernel still
 * sees the app's own unprivileged uid, so it grants no extra power over the phone. */
#ifndef NT_ARM_SYSTEM_CALL
#define NT_ARM_SYSTEM_CALL 0x404
#endif
static int is_id_getter(long nr) {
    return nr == SYS_getuid || nr == SYS_geteuid || nr == SYS_getgid || nr == SYS_getegid;
}
static int is_owner_setter(long nr) {
    if (nr == SYS_fchownat || nr == SYS_fchown) return 1;
#ifdef SYS_chown
    if (nr == SYS_chown || nr == SYS_lchown) return 1;
#endif
    return 0;
}
static int is_id_setter(long nr) {
    return is_owner_setter(nr) || nr == SYS_setuid || nr == SYS_setgid || nr == SYS_setreuid || nr == SYS_setregid || nr == SYS_setresuid
        || nr == SYS_setresgid || nr == SYS_setgroups || nr == SYS_setfsuid || nr == SYS_setfsgid;
}
/* Things zygote does as "root" that an unprivileged app can never do (they would fail with EPERM and zygote treats that as fatal):
 * dropping capabilities (capset, PR_CAPBSET_DROP) and the mount-namespace/mount calls used for storage isolation. With the virtual root they
 * "succeed" and do nothing: the guest gets no isolation and no extra power. */
static int is_priv_noop(long nr, regs_t *r) {
    if (nr == SYS_capset || nr == SYS_mount || nr == SYS_umount2) return 1;
    if (nr == SYS_prctl) return *ARG(r, 0) == 24 /*PR_CAPBSET_DROP*/;
    if (nr == SYS_unshare) return (*ARG(r, 0) & CLONE_NEWNS) != 0;
    return 0;
}
/* Make the kernel skip the syscall in progress; the exit hook then reports success. */
/* "Skip" a syscall by turning it into getpid(): its result is overwritten at syscall exit. (nr = -1 would be the usual trick, but Android's
 * seccomp filter answers an invalid syscall number with SIGSYS, which killed the guest process.) */
static int skip_syscall(pid_t pid, regs_t *r) {
#if defined(__aarch64__)
    int nr = (int)SYS_getpid; struct iovec v = { &nr, sizeof nr }; (void)r;
    return ptrace(PTRACE_SETREGSET, pid, (void *)NT_ARM_SYSTEM_CALL, &v);
#else
    R_NR(r) = (unsigned long)SYS_getpid; return set_regs(pid, r);
#endif
}
/* Rewrites st_uid/st_gid that belong to the host app uid so the guest sees root-owned files. */
static void fake_owner(pid_t pid, unsigned long buf, long nr) {
    long uo, go;
#ifdef SYS_statx
    if (nr == SYS_statx) { uo = 20; go = 24; } else
#endif
#if defined(__x86_64__)
    { uo = 28; go = 32; }
#else
    { uo = 24; go = 28; }
#endif
    unsigned v; unsigned zero = 0;
    if (!rd(pid, buf + uo, &v, 4) && v == HOSTUID) wr(pid, buf + uo, &zero, 4);
    if (!rd(pid, buf + go, &v, 4) && v == HOSTUID) wr(pid, buf + go, &zero, 4);
}
static int is_stat_call(long nr, int *bufarg) {
    if (nr == SYS_newfstatat) { *bufarg = 2; return 1; }
    if (nr == SYS_fstat) { *bufarg = 1; return 1; }
#ifdef SYS_statx
    if (nr == SYS_statx) { *bufarg = 4; return 1; }
#endif
#ifdef SYS_stat
    if (nr == SYS_stat || nr == SYS_lstat) { *bufarg = 1; return 1; }
#endif
    return 0;
}

static char RING[24][220]; static int RINGN; static int RING_FROZEN;
static void ring_dump(void) {
    int n = RINGN < 24 ? RINGN : 24;
    dprintf(2, "[crash] last %d failing syscalls before the end:\n", n);
    for (int k = n; k > 0; k--) dprintf(2, "[crash]   %s\n", RING[(RINGN - k) % 24]);
}

#include <stdarg.h>
#include "binder.h"
#include "propsvc.h"
#include "selinuxfs.h"

/* ------------------------------------------------------------------ enter / exit */
static void on_enter(pid_t pid, struct tp *t) {
    regs_t r; if (get_regs(pid, &r)) return;
    t->nr = (long)R_NR(&r); t->touched = 0; t->path[0] = 0;
    for (int i = 0; i < 6; i++) t->saved[i] = *ARG(&r, i);
    t->skip = 0;
    if (BINDER_ON && bnd_enter(pid, t, &r)) return;
    if (FAKEROOT && (is_id_setter(t->nr) || is_priv_noop(t->nr, &r))) { if (!skip_syscall(pid, &r)) t->skip = 1; return; }
    if (t->nr == SYS_openat
#ifdef SYS_open
        || t->nr == SYS_open
#endif
       ) { if (sel_open_hook(pid, t, &r)) return; }
    if (t->nr == SYS_connect || t->nr == SYS_bind) {
        /* guest unix sockets under /dev/socket live in <rootfs>/dev/socket (init-provided sockets, property_service, zygote ...) */
        unsigned long ap = *ARG(&r, 1); unsigned long len = *ARG(&r, 2); struct sockaddr_un su;
        if (len > 2 && len <= sizeof su && !rd(pid, ap, &su, len)) {
            if (len < sizeof su) ((char *)&su)[len] = 0;
            if (su.sun_family == AF_UNIX && !strncmp(su.sun_path, "/dev/socket/", 12)) {
                struct sockaddr_un nu; memset(&nu, 0, sizeof nu); nu.sun_family = AF_UNIX;
                if (LOGSOCK[0] && !strcmp(su.sun_path, "/dev/socket/logdw")) snprintf(nu.sun_path, sizeof nu.sun_path, "%s", LOGSOCK);
                else if (ROOTLEN + strlen(su.sun_path) < sizeof nu.sun_path) snprintf(nu.sun_path, sizeof nu.sun_path, "%s%s", ROOT, su.sun_path);
                if (nu.sun_path[0]) {
                    unsigned long dst = (R_SP(&r) - 256 - 8192) & ~15UL;
                    if (!wr(pid, dst, &nu, sizeof nu)) { *ARG(&r, 1) = dst; *ARG(&r, 2) = sizeof(sa_family_t) + strlen(nu.sun_path) + 1; t->touched |= 6; set_regs(pid, &r); }
                }
            }
        }
        return;
    }
    if (t->nr == SYS_execve) {
        char gp[PATH_MAX];
        if (NOCRASHDUMP && !rd_str(pid, *ARG(&r, 0), gp, sizeof gp) && strstr(gp, "crash_dump")) {
            if (!skip_syscall(pid, &r)) { t->skip = 1; t->skiperr = ENOENT; vlog("blocked %s", gp); }
            return;
        }
        if (rewrite_exec(pid, &r, t)) set_regs(pid, &r); return;
    }
    const struct sc *s = find_sc(t->nr); if (!s) return;
    unsigned long base = (R_SP(&r) - 256 - 8192) & ~15UL; unsigned long off = 0; int changed = 0;
    int idx[2] = { s->a0, s->a1 };
    for (int k = 0; k < 2; k++) {
        if (idx[k] < 0) continue;
        char g[PATH_MAX]; unsigned long p = *ARG(&r, idx[k]);
        if (!p || rd_str(pid, p, g, sizeof g)) continue;
        if (k == 0) snprintf(t->path, sizeof t->path, "%s", g);
        if (g[0] != '/') continue;
        int follow = 1;
        if (s->kind == 'n') follow = 0;
        else if (s->kind == 'o') follow = !(*ARG(&r, s->flagarg) & O_NOFOLLOW) ;
        else if (s->kind == 'a') follow = !(*ARG(&r, s->flagarg) & 0x100 /*AT_SYMLINK_NOFOLLOW*/);
        char h[PATH_MAX]; if (!to_host(g, follow, h)) continue;
        size_t l = strlen(h) + 1; unsigned long dst = base + off;
        if (wr(pid, dst, h, l)) continue;
        off += (l + 15) & ~15UL; *ARG(&r, idx[k]) = dst; t->touched |= 1UL << idx[k]; changed = 1;
        vlog("%ld %s -> %s", t->nr, g, h);
    }
    if (changed) set_regs(pid, &r);
}

static void on_exit_(pid_t pid, struct tp *t) {
    regs_t r; if (get_regs(pid, &r)) return;
    long ret = (long)R_RET(&r); int dirty = 0;
    if (t->selpath[0]) { unlink(t->selpath); t->selpath[0] = 0; }      /* the open of a /sys/fs/selinux request file is done: the fd keeps the scratch file alive */
    if (t->bemu) { R_RET(&r) = (unsigned long)t->bret; set_regs(pid, &r); t->bemu = 0; t->touched = 0; return; }
    if (BINDER_ON) bnd_exit(pid, t, ret);
    if (t->skip) { R_RET(&r) = t->skiperr ? (unsigned long)-(long)t->skiperr : 0; set_regs(pid, &r); t->skip = 0; t->skiperr = 0; t->touched = 0; return; }
    if (FAKEROOT) {
        int ba;
        if (is_id_getter(t->nr)) { R_RET(&r) = 0; set_regs(pid, &r); return; }
        if (t->nr == SYS_getresuid || t->nr == SYS_getresgid) {
            unsigned z[3] = { 0, 0, 0 };
            if (ret == 0) for (int k = 0; k < 3; k++) wr(pid, t->saved[k], &z[k], 4);
            return;
        }
        if (ret == 0 && is_stat_call(t->nr, &ba)) fake_owner(pid, t->saved[ba], t->nr);
    }
    if (DIAG && !TRACE && !RING_FROZEN && ret < 0 && ret > -4096 && t->nr != SYS_wait4)
        snprintf(RING[RINGN++ % 24], sizeof RING[0], "pid=%d %s(%ld) a0=%lx a1=%lx %.100s -> errno %ld", pid, sname(t->nr), t->nr, t->saved[0], t->saved[1], t->path, -ret);
    if (TRACE && ret < 0 && ret > -4096 && t->nr != SYS_wait4)
        dprintf(2, "[trace] pid=%d %s(%ld) a0=%lx a1=%lx a2=%lx %s -> errno %ld\n", pid, sname(t->nr), t->nr, t->saved[0], t->saved[1], t->saved[2], t->path, -ret);
    if (t->nr == SYS_statfs && ret == 0 && has_prefix(t->path, "/sys/fs/selinux")) {     /* libselinux only trusts a mount point whose f_type is SELINUX_MAGIC */
        uint64_t magic = SEL_MAGIC; wr(pid, t->saved[1], &magic, 8);
    }
    if (ret > 0 && (t->nr == SYS_readlinkat
#ifdef SYS_readlink
                    || t->nr == SYS_readlink
#endif
                   )) {
        int bi = t->nr == SYS_readlinkat ? 2 : 1; unsigned long bp = t->saved[bi]; size_t cap = (size_t)t->saved[bi + 1];
        char lb[PATH_MAX + 1]; size_t n = (size_t)ret < PATH_MAX ? (size_t)ret : PATH_MAX;
        if (n <= cap && !rd(pid, bp, lb, n)) {
            lb[n] = 0; const char *rest = NULL; size_t pl = 0;
            if (!strncmp(lb, ROOT, ROOTLEN) && (lb[ROOTLEN] == '/' || lb[ROOTLEN] == 0)) { rest = lb[ROOTLEN] ? lb + ROOTLEN : "/"; }
            for (int k = 0; !rest && k < NBIND; k++) { pl = strlen(BIND[k].host);
                if (!strncmp(lb, BIND[k].host, pl) && (lb[pl] == '/' || lb[pl] == 0)) { static char nb[PATH_MAX * 2]; snprintf(nb, sizeof nb, "%s%s", BIND[k].guest, lb + pl); rest = nb; } }
            if (rest) { size_t l = strlen(rest); if (l > cap) l = cap; if (!wr(pid, bp, rest, l)) { R_RET(&r) = l; dirty = 1; } }
        }
    }
    if (t->nr == SYS_getcwd && ret > 0) {
        char buf[PATH_MAX]; unsigned long p = t->saved[0];
        if (!rd_str(pid, p, buf, sizeof buf) && !strncmp(buf, ROOT, ROOTLEN) && (buf[ROOTLEN] == '/' || buf[ROOTLEN] == 0)) {
            const char *rest = buf[ROOTLEN] ? buf + ROOTLEN : "/";
            size_t l = strlen(rest) + 1; if (!wr(pid, p, rest, l)) { R_RET(&r) = l; dirty = 1; }
        }
    }
    if (t->touched && !(t->nr == SYS_execve && ret == 0)) {
        for (int i = 0; i < 6; i++) if ((t->touched >> i) & 1) { if (RET_IS_ARG0 && i == 0) continue; *ARG(&r, i) = t->saved[i]; dirty = 1; }
    }
    if (dirty) set_regs(pid, &r);
    t->touched = 0;
}



/* ------------------------------------------------------------------ guest log bridge
 * The guest has no logd, so ART's own error messages (the real reason for most startup crashes) vanish.
 * We bind a datagram socket next to the rootfs, redirect connect("/dev/socket/logdw") to it, and a small
 * helper process prints every message to stderr as "[guest-log] ...". */
static void start_logger(void) {
    const char *sl = strrchr(ROOT, '/'); if (!sl) return;
    int n = snprintf(LOGSOCK, sizeof LOGSOCK, "%.*s/.logdw", (int)(sl - ROOT), ROOT);
    if (n <= 0 || (size_t)n >= sizeof LOGSOCK) { LOGSOCK[0] = 0; return; }
    int fd = socket(AF_UNIX, SOCK_DGRAM, 0); if (fd < 0) { LOGSOCK[0] = 0; return; }
    struct sockaddr_un a; memset(&a, 0, sizeof a); a.sun_family = AF_UNIX; snprintf(a.sun_path, sizeof a.sun_path, "%s", LOGSOCK);
    unlink(LOGSOCK);
    if (bind(fd, (struct sockaddr *)&a, sizeof a) != 0) { close(fd); LOGSOCK[0] = 0; return; }
    chmod(LOGSOCK, 0666);
    LOGGER = fork();
    if (LOGGER < 0) { close(fd); LOGGER = 0; LOGSOCK[0] = 0; return; }
    if (LOGGER == 0) {
        prctl(PR_SET_PDEATHSIG, SIGKILL);
        static const char P[] = "??VDIWEF"; char b[4096];
        for (;;) {
            ssize_t k = recv(fd, b, sizeof b - 1, 0); if (k < 0) { if (errno == EINTR) continue; _exit(0); }
            if (k < 13) continue;
            b[k] = 0; int pr = (unsigned char)b[11]; const char *tag = b + 12; const char *msg = tag + strlen(tag) + 1;
            if (msg >= b + k) msg = "";
            if (!strcmp(tag, "cutils-trace")) continue;
            dprintf(2, "[guest-log] %c %s: %s\n", pr < 8 ? P[pr] : '?', tag, msg);
        }
    }
    close(fd);
}

/* ------------------------------------------------------------------ crash symbolizer (nearest ELF symbol to an address) */
static int sym_for(const char *file, unsigned long fileoff, char *out, size_t n) {
    int fd = open(file, O_RDONLY); if (fd < 0) return 0;
    struct stat st; if (fstat(fd, &st) || st.st_size < (off_t)sizeof(Elf64_Ehdr) || st.st_size > (off_t)1 << 30) { close(fd); return 0; }
    unsigned char *m = mmap(NULL, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0); close(fd);
    if (m == MAP_FAILED) return 0;
    int ok = 0; Elf64_Ehdr *eh = (Elf64_Ehdr *)m;
    if (memcmp(eh->e_ident, ELFMAG, SELFMAG) || eh->e_ident[EI_CLASS] != ELFCLASS64) goto done;
    unsigned long vaddr = fileoff; int found = 0;
    for (int i = 0; i < eh->e_phnum; i++) { Elf64_Phdr *ph = (Elf64_Phdr *)(m + eh->e_phoff + (size_t)i * eh->e_phentsize);
        if (ph->p_type == PT_LOAD && fileoff >= ph->p_offset && fileoff < ph->p_offset + ph->p_filesz) { vaddr = fileoff - ph->p_offset + ph->p_vaddr; found = 1; break; } }
    if (!found) goto done;
    Elf64_Shdr *sh = (Elf64_Shdr *)(m + eh->e_shoff); unsigned long best = 0; const char *bn = NULL; unsigned long bsz = 0;
    for (int i = 0; i < eh->e_shnum; i++) {
        if (sh[i].sh_type != SHT_DYNSYM && sh[i].sh_type != SHT_SYMTAB) continue;
        Elf64_Sym *sy = (Elf64_Sym *)(m + sh[i].sh_offset); size_t cnt = sh[i].sh_size / sizeof *sy; const char *strs = (const char *)(m + sh[sh[i].sh_link].sh_offset);
        for (size_t k = 0; k < cnt; k++) {
            if (ELF64_ST_TYPE(sy[k].st_info) != STT_FUNC || !sy[k].st_value || sy[k].st_value > vaddr) continue;
            if (!bn || sy[k].st_value > best) { best = sy[k].st_value; bn = strs + sy[k].st_name; bsz = sy[k].st_size; }
        }
    }
    if (bn) { snprintf(out, n, "%s+0x%lx%s", bn, vaddr - best, (bsz && vaddr - best >= bsz) ? " (nearest exported symbol; real function is not exported)" : ""); ok = 1; }
done:
    munmap(m, st.st_size); return ok;
}

/* ------------------------------------------------------------------ crash diagnostics (-d, or -s which also prints every failing syscall) */
static const char *maps_of(pid_t pid, unsigned long addr, char *out, size_t n) {
    char p[64]; snprintf(p, sizeof p, "/proc/%d/maps", pid); out[0] = 0;
    FILE *f = fopen(p, "r"); if (!f) return out;
    char line[PATH_MAX + 128];
    while (fgets(line, sizeof line, f)) {
        unsigned long lo, hi; if (sscanf(line, "%lx-%lx", &lo, &hi) != 2) continue;
        if (addr >= lo && addr < hi) { size_t l = strlen(line); if (l && line[l - 1] == '\n') line[l - 1] = 0; unsigned long fo = 0, a1_, a2_; sscanf(line, "%lx-%lx %*s %lx", &a1_, &a2_, &fo); snprintf(out, n, "%s (offset into map: 0x%lx, FILE OFFSET 0x%lx)", line, addr - lo, fo + addr - lo);
            { char *pa = strchr(line, '/'); char sym[300]; if (pa && sym_for(pa, fo + addr - lo, sym, sizeof sym)) { size_t ol = strlen(out); snprintf(out + ol, n - ol, "\n[signal]     in %s", sym); } }
            break; }
    }
    fclose(f); return out;
}
static void sigdiag(pid_t pid, int sig) {
    static int shown;
    if (!(TRACE || DIAG) || shown >= 12) return;
    if (sig != SIGSEGV && sig != SIGBUS && sig != SIGILL && sig != SIGABRT && sig != SIGFPE && sig != SIGSYS) return;
    shown++; if (sig == SIGSEGV || sig == SIGBUS || sig == SIGILL || sig == SIGABRT) { if (!RING_FROZEN && !TRACE) ring_dump(); RING_FROZEN = 1; }
    siginfo_t si; memset(&si, 0, sizeof si); ptrace(PTRACE_GETSIGINFO, pid, 0, &si);
    regs_t r; char m[PATH_MAX + 160], m2[PATH_MAX + 160];
    dprintf(2, "[signal] pid=%d sig=%d code=%d fault_addr=%p\n", pid, sig, si.si_code, si.si_addr);
    if (!get_regs(pid, &r)) {
        dprintf(2, "[signal]   pc=%lx %s\n", (unsigned long)R_PC(&r), maps_of(pid, (unsigned long)R_PC(&r), m, sizeof m));
        if (R_LR(&r)) dprintf(2, "[signal]   lr=%lx %s\n", (unsigned long)R_LR(&r), maps_of(pid, (unsigned long)R_LR(&r), m2, sizeof m2));
#if defined(__aarch64__)
        if (sig == SIGSEGV || sig == SIGBUS) {      /* registers + the instructions around pc, to decode the faulting access offline (llvm-objdump) */
            for (int i = 0; i < 31; i += 4) dprintf(2, "[signal]   x%-2d=%016llx x%-2d=%016llx x%-2d=%016llx x%-2d=%016llx\n", i, r.regs[i], i + 1, r.regs[i + 1], i + 2, r.regs[i + 2], i + 3, i + 3 < 31 ? r.regs[i + 3] : 0ULL);
            unsigned code[10]; if (!rd(pid, (unsigned long)R_PC(&r) - 24, code, sizeof code)) { dprintf(2, "[signal]   code@pc-24:"); for (int i = 0; i < 10; i++) dprintf(2, " %08x", code[i]); dprintf(2, "\n"); }
        }
#endif
        /* frame-pointer backtrace (Android's arm64 libs keep frame pointers): shows who called the crashing function */
        { unsigned long fp = (unsigned long)R_FP(&r);
          for (int i = 0; i < 20 && fp && !(fp & 7); i++) {
              unsigned long fr[2]; if (rd(pid, fp, fr, sizeof fr) || !fr[1]) break;
              dprintf(2, "[signal]   #%02d pc=%lx %s\n", i, fr[1], maps_of(pid, fr[1], m2, sizeof m2));
              if (fr[0] <= fp) break; fp = fr[0];
          } }
    }
}


#include <ucontext.h>
/* Android's seccomp filter for apps answers a forbidden syscall with SIGSYS (which kills the process). Turn it into ENOSYS and say which one. */
static void sigsys_handler(int sig, siginfo_t *si, void *ucv) {
    (void)sig; int nr = *(int *)((char *)si + 24);
    char b[96]; int n = snprintf(b, sizeof b, "[seccomp] syscall %d is blocked for this app (pid %d), returning ENOSYS\n", nr, (int)getpid()); (void)!write(2, b, (size_t)n);
    ucontext_t *uc = (ucontext_t *)ucv;
#if defined(__aarch64__)
    uc->uc_mcontext.regs[0] = (unsigned long)(-ENOSYS);
#elif defined(__x86_64__)
    uc->uc_mcontext.gregs[REG_RAX] = -ENOSYS;
#endif
}
static void install_sigsys(void) { struct sigaction sa; memset(&sa, 0, sizeof sa); sa.sa_sigaction = sigsys_handler; sa.sa_flags = SA_SIGINFO; sigaction(SIGSYS, &sa, NULL); }

static char *EXTRA[8]; static int NEXTRA; static char *SOCKS[8]; static int NSOCK; static pid_t PROPSVC;

/* ------------------------------------------------------------------ main */
static void usage(void) { dprintf(2, "usage: vmloader -r ROOTFS [-b HOST:GUEST]... [-w GUESTCWD] [-x \"BGPROG ARGS\"]... [-N] [-v] [-s] [-d] [-0] -- PROGRAM [ARGS...]\n"); exit(2); }

int main(int argc, char **argv) {
    int i = 1; snprintf(GCWD, sizeof GCWD, "/");
    for (; i < argc; i++) {
        if (!strcmp(argv[i], "--")) { i++; break; }
        else if (!strcmp(argv[i], "-r") && i + 1 < argc) { if (!realpath(argv[++i], ROOT)) { perror("rootfs"); return 2; } }
        else if (!strcmp(argv[i], "-b") && i + 1 < argc && NBIND < 16) {
            char *c = strchr(argv[++i], ':'); if (!c) usage(); *c = 0;
            snprintf(BIND[NBIND].host, PATH_MAX, "%s", argv[i]); snprintf(BIND[NBIND].guest, PATH_MAX, "%s", c + 1); NBIND++;
        } else if (!strcmp(argv[i], "-w") && i + 1 < argc) snprintf(GCWD, sizeof GCWD, "%s", argv[++i]);
        else if (!strcmp(argv[i], "-v")) VERBOSE = 1;
        else if (!strcmp(argv[i], "-s")) TRACE = 1;
        else if (!strcmp(argv[i], "-d")) DIAG = 1;
        else if (!strcmp(argv[i], "-0")) { FAKEROOT = 1; HOSTUID = getuid(); }
        else if (!strcmp(argv[i], "-x") && i + 1 < argc && NEXTRA < 8) EXTRA[NEXTRA++] = argv[++i];   /* extra background guest program (init-style service) */
        else if (!strcmp(argv[i], "-S") && i + 1 < argc && NSOCK < 8) SOCKS[NSOCK++] = argv[++i];   /* init-style socket: NAME or NAME:dgram */
        else if (!strcmp(argv[i], "-N")) BDEBUG = 1;          /* log binder driver activity */
        else if (!strcmp(argv[i], "-n")) BINDER_ON = 0;       /* do not emulate binder */
        else if (!strcmp(argv[i], "-C")) NOCRASHDUMP = 0;   /* let the guest start crash_dump (only works when not ptraced) */
        else if (!strcmp(argv[i], "-V")) { printf("vmloader features: selinuxfs privnoop attrwrite\n"); return 0; }   /* GuestBoot checks this to detect a stale prebuilt loader */
        else usage();
    }
    if (!ROOT[0] || i >= argc) usage();
    ROOTLEN = strlen(ROOT); if (ROOTLEN > 1 && ROOT[ROOTLEN - 1] == '/') ROOT[--ROOTLEN] = 0;

    { char h0[PATH_MAX]; to_host(GCWD, 1, h0); if (chdir(h0) != 0 && chdir(ROOT) != 0) { perror("chdir"); return 1; } }
    setup_selinuxfs();
    if (DIAG || TRACE) start_logger();
    { const char *sl = strrchr(ROOT, '/'); if (sl && BINDER_ON) { int n = snprintf(BDIR, sizeof BDIR, "%.*s/.binder", (int)(sl - ROOT), ROOT); if (n <= 0 || (size_t)n >= sizeof BDIR || (mkdir(BDIR, 0700) && errno != EEXIST)) BDIR[0] = 0; } }
    /* property service + init-style sockets (ANDROID_SOCKET_<name> carries the fd, like init does) */
    install_sigsys();
    if (BINDER_ON) { PROPSVC = start_propsvc(ROOT, NULL); }
    for (int k = 0; k < NSOCK; k++) {
        char nm[64]; snprintf(nm, sizeof nm, "%s", SOCKS[k]); int dg = 0; char *c = strchr(nm, ':'); if (c) { *c = 0; dg = !strcmp(c + 1, "dgram"); }
        char dir[PATH_MAX], sp[PATH_MAX]; snprintf(dir, sizeof dir, "%s/dev/socket", ROOT); mkdir(dir, 0777); snprintf(sp, sizeof sp, "%s/%s", dir, nm);
        int sfd = socket(AF_UNIX, dg ? SOCK_DGRAM : SOCK_STREAM, 0); if (sfd < 0) continue;
        struct sockaddr_un sa; memset(&sa, 0, sizeof sa); sa.sun_family = AF_UNIX;
        if (strlen(sp) >= sizeof sa.sun_path) { close(sfd); continue; }
        snprintf(sa.sun_path, sizeof sa.sun_path, "%s", sp); unlink(sp);
        if (bind(sfd, (struct sockaddr *)&sa, sizeof sa) || (!dg && listen(sfd, 32))) { close(sfd); continue; }
        chmod(sp, 0666);
        int fl = fcntl(sfd, F_GETFD); fcntl(sfd, F_SETFD, fl & ~FD_CLOEXEC);
        char ev[96], val[16]; snprintf(ev, sizeof ev, "ANDROID_SOCKET_%s", nm); snprintf(val, sizeof val, "%d", sfd); setenv(ev, val, 1);
    }
    pid_t child = fork();
    if (child < 0) { perror("fork"); return 1; }
    if (child == 0) {
        ptrace(PTRACE_TRACEME, 0, 0, 0);
        raise(SIGSTOP);
        execv(argv[i], &argv[i]);
        dprintf(2, "vmloader: cannot start %s: %s\n", argv[i], strerror(errno));
        _exit(127);
    }
    pid_t extra_pids[8]; int nx = 0;
    for (int k = 0; k < NEXTRA; k++) {
        char *av[32]; int ac = 0; char *cp = EXTRA[k];
        for (char *tok = strtok(cp, " "); tok && ac < 31; tok = strtok(NULL, " ")) av[ac++] = tok;
        av[ac] = NULL; if (!ac) continue;
        pid_t xp = fork();
        if (xp == 0) { ptrace(PTRACE_TRACEME, 0, 0, 0); raise(SIGSTOP); execv(av[0], av); dprintf(2, "vmloader: cannot start %s: %s\n", av[0], strerror(errno)); _exit(127); }
        if (xp > 0) extra_pids[nx++] = xp;
    }
    int status; int exit_code = 0; int alive = 0;
    long opts = PTRACE_O_TRACESYSGOOD | PTRACE_O_TRACEFORK | PTRACE_O_TRACEVFORK | PTRACE_O_TRACECLONE | PTRACE_O_TRACEEXEC | PTRACE_O_EXITKILL;
    for (;;) {
        pid_t pid = waitpid(-1, &status, __WALL);
        if (pid < 0) { if (errno == EINTR) continue; break; }
        if ((pid == LOGGER && LOGGER) || (pid == PROPSVC && PROPSVC)) continue;       /* helper processes are not guest tracees */
        struct tp *t = tget(pid, 0);
        if (WIFEXITED(status) || WIFSIGNALED(status)) {
            if (!t) { if (WIFSIGNALED(status) && (TRACE || DIAG)) dprintf(2, "[signal] untraced pid=%d killed by signal %d\n", pid, WTERMSIG(status)); continue; }
            if (t) { pid_t tg = t->tgid; t->pid = 0; alive--; if (BINDER_ON) { int left = 0; for (int q = 0; q < MAXP; q++) if (T[q].pid && T[q].tgid == tg) left = 1; b_thread_gone(pid, tg, !left); } }
            if ((TRACE || DIAG) && WIFSIGNALED(status)) { dprintf(2, "[signal] pid=%d killed by signal %d\n", pid, WTERMSIG(status)); if (!TRACE && !RING_FROZEN) { static int dumps; if (dumps++ < 3) ring_dump(); } }
            if (pid == child) { exit_code = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status); for (int q = 0; q < MAXP; q++) if (T[q].pid && T[q].pid != pid) kill(T[q].pid, SIGKILL); for (int q = 0; q < nx; q++) kill(extra_pids[q], SIGKILL); }
            if (alive <= 0) break;
            continue;
        }
        if (!WIFSTOPPED(status)) continue;
        int sig = WSTOPSIG(status);
        if (!t) { t = tget(pid, 1); alive++; if (t) t->tgid = b_tgid_of(pid); if (pid == child) ptrace(PTRACE_SETOPTIONS, pid, 0, (void *)opts);
                  ptrace(PTRACE_SETOPTIONS, pid, 0, (void *)opts);
                  if (sig == SIGSTOP) { ptrace(PTRACE_SYSCALL, pid, 0, 0); continue; } }
        if (sig == (SIGTRAP | 0x80)) {
            if (!t->in_sys) { t->in_sys = 1; on_enter(pid, t); if (t->deferred) continue; } else { t->in_sys = 0; on_exit_(pid, t); }
            ptrace(PTRACE_SYSCALL, pid, 0, 0); continue;
        }
        if ((status >> 16) != 0) { ptrace(PTRACE_SYSCALL, pid, 0, 0); continue; }   /* fork/clone/exec events */
        if (sig == SIGSTOP || sig == SIGTRAP) { ptrace(PTRACE_SYSCALL, pid, 0, 0); continue; }
        if (sig == SIGSYS) {
            siginfo_t si; static int shown_sys;
            if (!ptrace(PTRACE_GETSIGINFO, pid, 0, &si) && si.si_code == 1 /*SYS_SECCOMP*/) {
                regs_t sr; if (!get_regs(pid, &sr)) {
                    if (shown_sys++ < 40) dprintf(2, "[seccomp] guest pid=%d: syscall %d (a0=%lx a1=%lx a2=%lx) is blocked for this app -> ENOSYS\n", pid, *(int *)((char *)&si + 24),
                                                  (unsigned long)*ARG(&sr, 0), (unsigned long)*ARG(&sr, 1), (unsigned long)*ARG(&sr, 2));
                    R_RET(&sr) = (unsigned long)(-ENOSYS); set_regs(pid, &sr);
                }
                ptrace(PTRACE_SYSCALL, pid, 0, 0); continue;
            }
        }
        if (TRACE) dprintf(2, "[trace] pid=%d signal %d\n", pid, sig);
        sigdiag(pid, sig);
        ptrace(PTRACE_SYSCALL, pid, 0, (void *)(long)sig);                           /* real signal: pass it on */
    }
    if (PROPSVC) kill(PROPSVC, SIGTERM);
    if (LOGGER) { usleep(150000); kill(LOGGER, SIGTERM); waitpid(LOGGER, NULL, 0); unlink(LOGSOCK); }
    return exit_code;
}
