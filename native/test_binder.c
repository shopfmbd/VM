/* Binder self-test: a context manager, a service and a client talk through vmloader's emulated /dev/binder.
 * Build for the host or the phone's ABI, run:  vmloader -r ROOTFS -0 -N -- /test_binder  (exit 0 + "PASS" = binder emulation works) */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/wait.h>
#include <poll.h>
#include <unistd.h>
#include <signal.h>

#define B_PACK(a, b, c, d) ((((uint32_t)(a)) << 24) | (((uint32_t)(b)) << 16) | (((uint32_t)(c)) << 8) | (uint32_t)(d))
#define BT_BINDER B_PACK('s', 'b', '*', 0x85)
#define BT_HANDLE B_PACK('s', 'h', '*', 0x85)
struct tr { uint64_t target, cookie; uint32_t code, flags; int32_t pid; uint32_t euid; uint64_t dsz, osz, buf, offs; };
struct bwr { uint64_t ws, wc, wb, rs, rc, rb; };
struct fbo { uint32_t type, flags; uint64_t binder; uint64_t cookie; };
#define BC_TRANSACTION _IOW('c', 0, struct tr)
#define BC_REPLY _IOW('c', 1, struct tr)
#define BC_FREE_BUFFER _IOW('c', 3, uint64_t)
#define BC_ENTER_LOOPER _IO('c', 12)
#define BC_REGISTER_LOOPER _IO('c', 11)
#define BR_TRANSACTION _IOR('r', 2, struct tr)
#define BR_REPLY _IOR('r', 3, struct tr)
#define BR_TRANSACTION_COMPLETE _IO('r', 6)
#define BR_NOOP _IO('r', 12)
#define BR_SPAWN_LOOPER _IO('r', 13)
#define BR_INCREFS _IOR('r', 7, uint64_t[2])
#define BR_ACQUIRE _IOR('r', 8, uint64_t[2])
#define BR_DEAD_REPLY _IO('r', 5)
#define BR_FAILED_REPLY _IO('r', 17)
struct bufobj { uint32_t type, flags; uint64_t buffer, length, parent, parent_offset; };
struct fdaobj { uint32_t type, pad; uint64_t num_fds, parent, parent_offset; };
struct trsg { struct tr tr; uint64_t buffers_size; };
#define BT_FD B_PACK('f', 'd', '*', 0x85)
#define BT_FDA B_PACK('f', 'd', 'a', 0x85)
#define BT_PTR B_PACK('p', 't', '*', 0x85)
#define BC_TRANSACTION_SG _IOW('c', 17, struct trsg)
#define BINDER_WRITE_READ _IOWR('b', 1, struct bwr)
#define BINDER_VERSION _IOWR('b', 9, int32_t)
#define BINDER_SET_CONTEXT_MGR _IOW('b', 7, int32_t)
#define BINDER_SET_MAX_THREADS _IOW('b', 5, uint32_t)

static int fd; static unsigned char *map; static const char *me;
static void die(const char *m) { fprintf(stderr, "[%s] FAIL: %s (errno %d)\n", me, m, errno); _exit(1); }
static void bopen(void) {
    fd = open("/dev/binder", O_RDWR | O_CLOEXEC); if (fd < 0) die("open /dev/binder");
    int32_t v = 0; if (ioctl(fd, BINDER_VERSION, &v) || v != 8) die("BINDER_VERSION");
    uint32_t mt = 4; if (ioctl(fd, BINDER_SET_MAX_THREADS, &mt)) die("SET_MAX_THREADS");
    map = mmap(NULL, 1024 * 1024 - 8192, PROT_READ, MAP_PRIVATE | MAP_NORESERVE, fd, 0); if (map == MAP_FAILED) die("mmap binder");
}
static unsigned char wbuf[512], rbuf[1024]; static size_t wlen;
static void wcmd(uint32_t cmd, const void *arg, size_t n) { memcpy(wbuf + wlen, &cmd, 4); wlen += 4; if (n) { memcpy(wbuf + wlen, arg, n); wlen += n; } }
/* one WRITE_READ round: sends wbuf, reads up to rbuf */
static int talk(int do_read) {
    struct bwr b = { wlen, 0, (uint64_t)(uintptr_t)wbuf, do_read ? sizeof rbuf : 0, 0, (uint64_t)(uintptr_t)rbuf };
    int r = ioctl(fd, BINDER_WRITE_READ, &b); wlen = 0; if (r) return -1; return (int)b.rc;
}
typedef struct { struct tr tr; int has; uint32_t kind; } Ev;
/* parses rbuf, returns the first BR_TRANSACTION/BR_REPLY (or kind of failure) */
static Ev parse(int n) {
    Ev e; memset(&e, 0, sizeof e); int pos = 0;
    while (pos + 4 <= n) {
        uint32_t c; memcpy(&c, rbuf + pos, 4); pos += 4; size_t sz = (c >> 16) & 0x3fff;
        if (c == BR_TRANSACTION || c == BR_REPLY) { memcpy(&e.tr, rbuf + pos, sizeof e.tr); e.has = 1; e.kind = c; }
        else if (c == BR_DEAD_REPLY || c == BR_FAILED_REPLY) { e.kind = c; }
        pos += sz;
    }
    return e;
}
static void reply(const void *data, size_t n, const struct fbo *obj) {
    struct tr t; memset(&t, 0, sizeof t); static uint64_t offs[1] = { 0 };
    static unsigned char buf[256]; size_t k = 0; if (obj) { memcpy(buf, obj, sizeof *obj); k = sizeof *obj; } memcpy(buf + k, data, n);
    t.dsz = k + n; t.buf = (uint64_t)(uintptr_t)buf; t.osz = obj ? 8 : 0; t.offs = (uint64_t)(uintptr_t)offs;
    wcmd(BC_REPLY, &t, sizeof t);
}
static void free_buf(uint64_t b) { wcmd(BC_FREE_BUFFER, &b, 8); }
static Ev call(uint32_t handle, uint32_t code, const void *data, size_t n, const struct fbo *obj) {
    struct tr t; memset(&t, 0, sizeof t); static uint64_t offs[1] = { 0 };
    static unsigned char buf[256]; size_t k = 0; if (obj) { memcpy(buf, obj, sizeof *obj); k = sizeof *obj; } memcpy(buf + k, data, n);
    t.target = handle; t.code = code; t.dsz = k + n; t.buf = (uint64_t)(uintptr_t)buf; t.osz = obj ? 8 : 0; t.offs = (uint64_t)(uintptr_t)offs;
    wcmd(BC_TRANSACTION, &t, sizeof t);
    for (;;) { int n2 = talk(1); if (n2 < 0) die("WRITE_READ in call"); Ev e = parse(n2); if (e.kind == BR_REPLY || e.kind == BR_DEAD_REPLY || e.kind == BR_FAILED_REPLY) return e; }
}

static void context_manager(void) {
    me = "ctxmgr"; bopen();
    int32_t z = 0; if (ioctl(fd, BINDER_SET_CONTEXT_MGR, &z)) die("SET_CONTEXT_MGR");
    wcmd(BC_ENTER_LOOPER, NULL, 0);
    uint32_t svc_handle = 0;
    for (;;) {
        int n = talk(1); if (n < 0) die("ctxmgr read");
        Ev e = parse(n); if (!e.has || e.kind != BR_TRANSACTION) continue;
        unsigned char *data = (unsigned char *)(uintptr_t)e.tr.buf;
        if (e.tr.code == 1) {                       /* ADD_SERVICE: fbo + name */
            struct fbo o; memcpy(&o, data, sizeof o);
            if (o.type != BT_HANDLE) die("ADD_SERVICE object was not converted to a handle");
            svc_handle = (uint32_t)o.binder; fprintf(stderr, "[ctxmgr] service registered, my handle=%u name=%s\n", svc_handle, data + sizeof o);
            free_buf(e.tr.buf); reply("ok", 3, NULL);
        } else if (e.tr.code == 2) {                /* GET_SERVICE */
            free_buf(e.tr.buf);
            if (!svc_handle) { reply("none", 5, NULL); }
            else { struct fbo o = { BT_HANDLE, 0x7f, svc_handle, 0 }; reply("svc", 4, &o); }
        } else { free_buf(e.tr.buf); reply("?", 2, NULL); }
    }
}
static void service(void) {
    me = "service"; bopen(); usleep(300000);
    struct fbo o = { BT_BINDER, 0x7f, 0x1234, 0x5678 };
    Ev e = call(0, 1, "myservice", 10, &o);
    if (!e.has || memcmp((void *)(uintptr_t)e.tr.buf, "ok", 3)) die("ADD_SERVICE reply");
    wlen = 0; free_buf(e.tr.buf); wcmd(BC_ENTER_LOOPER, NULL, 0);
    for (;;) {
        int n = talk(1); if (n < 0) die("service read");
        Ev v = parse(n); if (!v.has || v.kind != BR_TRANSACTION) continue;
        if (v.tr.target != 0x1234 || v.tr.cookie != 0x5678) die("service got wrong ptr/cookie");
        if (v.tr.code == 100) {
            char out[64]; snprintf(out, sizeof out, "pong:%s", (char *)(uintptr_t)v.tr.buf);
            free_buf(v.tr.buf); reply(out, strlen(out) + 1, NULL);
        } else if (v.tr.code == 102) {              /* fd passing: data = fbo(BT_FD) */
            struct fbo o; memcpy(&o, (void *)(uintptr_t)v.tr.buf, sizeof o); char got[64] = {0};
            if (o.type != BT_FD) die("code 102: object type is not BT_FD");
            int rfd = (int)(uint32_t)o.binder; ssize_t k = read(rfd, got, sizeof got - 1); if (k <= 0) { fprintf(stderr, "[service] read from passed fd %d failed: %d\n", rfd, errno); die("code 102: passed fd unreadable"); }
            fprintf(stderr, "[service] received fd %d, read: %s\n", rfd, got); close(rfd);
            char out[96]; snprintf(out, sizeof out, "fd-ok:%s", got); free_buf(v.tr.buf); reply(out, strlen(out) + 1, NULL);
        } else if (v.tr.code == 103) {              /* scatter-gather: PTR(parent) PTR(child, has parent) FDA */
            unsigned char *d = (unsigned char *)(uintptr_t)v.tr.buf; struct bufobj *p0 = (struct bufobj *)d, *p1 = (struct bufobj *)(d + 40); struct fdaobj *fa = (struct fdaobj *)(d + 80);
            if (p0->type != BT_PTR || p1->type != BT_PTR || fa->type != BT_FDA) die("code 103: wrong object types");
            unsigned char *P = (unsigned char *)(uintptr_t)p0->buffer; uint64_t childp; memcpy(&childp, P, 8);
            if (childp != p1->buffer) die("code 103: parent pointer was not fixed up");
            int32_t fd0; memcpy(&fd0, P + fa->parent_offset, 4); char got[64] = {0}; if (read(fd0, got, sizeof got - 1) <= 0) die("code 103: fd in array unreadable");
            char out[160]; snprintf(out, sizeof out, "sg-ok:%s:%s", (char *)(uintptr_t)p1->buffer, got); close(fd0); free_buf(v.tr.buf); reply(out, strlen(out) + 1, NULL);
        } else if (v.tr.code == 101) {              /* oneway: just note it */
            fprintf(stderr, "[service] oneway got: %s\n", (char *)(uintptr_t)v.tr.buf); free_buf(v.tr.buf);
        } else { free_buf(v.tr.buf); reply("?", 2, NULL); }
    }
}
static int client(void) {
    me = "client"; bopen(); usleep(900000);
    Ev e = call(0, 2, "", 1, NULL);
    if (!e.has) die("GET_SERVICE failed");
    struct fbo o; memcpy(&o, (void *)(uintptr_t)e.tr.buf, sizeof o);
    if (o.type != BT_HANDLE || o.binder == 0) die("GET_SERVICE returned no handle");
    uint32_t h = (uint32_t)o.binder; fprintf(stderr, "[client] got service handle %u\n", h); wlen = 0; free_buf(e.tr.buf);
    Ev r = call(h, 100, "hello", 6, NULL);
    if (!r.has || strcmp((char *)(uintptr_t)r.tr.buf, "pong:hello")) die("ping/pong reply mismatch");
    fprintf(stderr, "[client] reply: %s\n", (char *)(uintptr_t)r.tr.buf); wlen = 0; free_buf(r.tr.buf);
    /* oneway */
    struct tr t; memset(&t, 0, sizeof t); t.target = h; t.code = 101; t.flags = 1; static char msg[] = "async!"; t.dsz = sizeof msg; t.buf = (uint64_t)(uintptr_t)msg;
    wcmd(BC_TRANSACTION, &t, sizeof t); if (talk(1) < 0) die("oneway"); usleep(150000);
    /* many calls */
    for (int i = 0; i < 50; i++) { char m[16]; snprintf(m, sizeof m, "n%d", i); Ev q = call(h, 100, m, strlen(m) + 1, NULL); char want[32]; snprintf(want, sizeof want, "pong:%s", m);
        if (!q.has || strcmp((char *)(uintptr_t)q.tr.buf, want)) die("loop reply mismatch"); wlen = 0; free_buf(q.tr.buf); }
    /* file descriptor passing */
    { int pf[2]; if (pipe(pf)) die("pipe"); if (write(pf[1], "pipedata", 8) != 8) die("pipe write");
      struct fbo fo = { BT_FD, 0x7f, (uint64_t)pf[0], 0 }; Ev fr = call(h, 102, "", 1, &fo);
      if (!fr.has || strcmp((char *)(uintptr_t)fr.tr.buf, "fd-ok:pipedata")) die("fd passing reply mismatch");
      fprintf(stderr, "[client] fd passing: %s\n", (char *)(uintptr_t)fr.tr.buf); wlen = 0; free_buf(fr.tr.buf); close(pf[0]); close(pf[1]); }
    /* scatter-gather + fd array (hwbinder style) */
    { int pf[2]; if (pipe(pf)) die("pipe2"); if (write(pf[1], "sgfd", 4) != 4) die("pipe2 write");
      static struct { uint64_t child; int32_t fd; int32_t pad; } P; static char C[16] = "child-string";
      P.child = (uint64_t)(uintptr_t)C; P.fd = pf[0];
      static struct { struct bufobj a, b; struct fdaobj c; } D; D.a = (struct bufobj){ BT_PTR, 0, (uint64_t)(uintptr_t)&P, sizeof P, 0, 0 };
      D.b = (struct bufobj){ BT_PTR, 1, (uint64_t)(uintptr_t)C, sizeof C, 0, 0 }; D.c = (struct fdaobj){ BT_FDA, 0, 1, 0, 8 };
      static uint64_t offs[3] = { 0, 40, 80 }; struct trsg t; memset(&t, 0, sizeof t); t.tr.target = h; t.tr.code = 103; t.tr.dsz = sizeof D; t.tr.buf = (uint64_t)(uintptr_t)&D; t.tr.osz = sizeof offs; t.tr.offs = (uint64_t)(uintptr_t)offs;
      t.buffers_size = sizeof P + sizeof C; wcmd(BC_TRANSACTION_SG, &t, sizeof t);
      Ev sr; memset(&sr, 0, sizeof sr); for (;;) { int n3 = talk(1); if (n3 < 0) die("sg talk"); sr = parse(n3); if (sr.kind == BR_REPLY || sr.kind == BR_DEAD_REPLY || sr.kind == BR_FAILED_REPLY) break; }
      if (!sr.has || strcmp((char *)(uintptr_t)sr.tr.buf, "sg-ok:child-string:sgfd")) die("scatter-gather reply mismatch");
      fprintf(stderr, "[client] scatter-gather: %s\n", (char *)(uintptr_t)sr.tr.buf); wlen = 0; free_buf(sr.tr.buf); close(pf[0]); close(pf[1]); }
    /* bad handle */
    Ev bad = call(9999, 100, "x", 2, NULL); if (bad.kind != BR_FAILED_REPLY) die("bad handle should fail");
    usleep(200000);
    fprintf(stderr, "[client] PASS\n"); return 0;
}
int main(int argc, char **argv) {
    if (argc > 1) { if (!strcmp(argv[1], "ctxmgr")) context_manager(); if (!strcmp(argv[1], "service")) service(); if (!strcmp(argv[1], "client")) return client(); }
    pid_t a = fork(); if (a == 0) context_manager();
    pid_t b = fork(); if (b == 0) service();
    pid_t c = fork(); if (c == 0) _exit(client());
    int st = 0; waitpid(c, &st, 0); kill(a, SIGKILL); kill(b, SIGKILL); waitpid(a, NULL, 0); waitpid(b, NULL, 0);
    if (WIFEXITED(st) && WEXITSTATUS(st) == 0) { fprintf(stderr, "PASS\n"); return 0; }
    fprintf(stderr, "FAIL (client status %d)\n", st); return 1;
}
