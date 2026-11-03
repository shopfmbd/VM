/*
 * binder.h - userspace emulation of the Linux binder driver, used by vmloader (stage 4).
 *
 * An unprivileged app cannot create its own binder context (and must not touch the phone's real one), so the
 * guest's open("/dev/binder"), ioctl() and mmap() on it are executed HERE by the supervisor instead of the kernel:
 *   open   -> the guest gets a FIFO fd (so poll/epoll work); we keep the other end to signal "work available"
 *   mmap   -> turned into an anonymous mapping; we write transaction buffers into it
 *   ioctl  -> BINDER_VERSION / SET_MAX_THREADS / SET_CONTEXT_MGR(_EXT) / WRITE_READ ... emulated, the real syscall is skipped
 * A blocking BINDER_WRITE_READ is "deferred": the tracee is left stopped at syscall entry until work arrives.
 * Included from vmloader.c (needs rd/wr/regs helpers, struct tp). Handles the binder protocol of Android 12 (v8, 64-bit):
 * transactions, replies, flat_binder_object translation (binder/handle), thread pools (BR_SPAWN_LOOPER), death notifications.
 * Not implemented yet: BINDER_TYPE_FD passing, BINDER_TYPE_PTR / scatter-gather (hwbinder), oneway ordering, freezing.
 */
#include <sys/ioctl.h>
#ifndef _IOC_SIZE
#define _IOC_SIZE(n) (((n) >> 16) & 0x3fff)
#endif
#ifndef _IOC_TYPE
#define _IOC_TYPE(n) (((n) >> 8) & 0xff)
#endif

#define B_PACK(a, b, c, d) ((((uint32_t)(a)) << 24) | (((uint32_t)(b)) << 16) | (((uint32_t)(c)) << 8) | (uint32_t)(d))
#define BT_BINDER  B_PACK('s', 'b', '*', 0x85)
#define BT_WBINDER B_PACK('w', 'b', '*', 0x85)
#define BT_HANDLE  B_PACK('s', 'h', '*', 0x85)
#define BT_WHANDLE B_PACK('w', 'h', '*', 0x85)
#define BT_FD      B_PACK('f', 'd', '*', 0x85)
#define BT_FDA     B_PACK('f', 'd', 'a', 0x85)
#define BT_PTR     B_PACK('p', 't', '*', 0x85)

struct b_tr { uint64_t target, cookie; uint32_t code, flags; int32_t pid; uint32_t euid; uint64_t data_size, offsets_size, buffer, offsets; };
struct b_bwr { uint64_t write_size, write_consumed, write_buffer, read_size, read_consumed, read_buffer; };
struct b_fbo { uint32_t type, flags; uint64_t binder; uint64_t cookie; };
struct b_pc { uint64_t ptr, cookie; };
struct b_bufobj { uint32_t type, flags; uint64_t buffer, length, parent, parent_offset; };   /* BINDER_TYPE_PTR */
struct b_fdaobj { uint32_t type, pad; uint64_t num_fds, parent, parent_offset; };           /* BINDER_TYPE_FDA */
struct b_tr_sg { struct b_tr tr; uint64_t buffers_size; };

#define BR_ERROR_            _IOR('r', 0, int)
#define BR_TRANSACTION_      _IOR('r', 2, struct b_tr)
#define BR_REPLY_            _IOR('r', 3, struct b_tr)
#define BR_DEAD_REPLY_       _IO('r', 5)
#define BR_TRANSACTION_COMPLETE_ _IO('r', 6)
#define BR_INCREFS_          _IOR('r', 7, struct b_pc)
#define BR_ACQUIRE_          _IOR('r', 8, struct b_pc)
#define BR_NOOP_             _IO('r', 12)
#define BR_SPAWN_LOOPER_     _IO('r', 13)
#define BR_DEAD_BINDER_      _IOR('r', 15, uint64_t)
#define BR_CLEAR_DEATH_NOTIFICATION_DONE_ _IOR('r', 16, uint64_t)
#define BR_FAILED_REPLY_     _IO('r', 17)

#define BC_TRANSACTION_      _IOW('c', 0, struct b_tr)
#define BC_REPLY_            _IOW('c', 1, struct b_tr)
#define BC_TRANSACTION_SG_   _IOW('c', 17, struct b_tr_sg)
#define BC_REPLY_SG_         _IOW('c', 18, struct b_tr_sg)
#define BC_FREE_BUFFER_      _IOW('c', 3, uint64_t)
#define BC_INCREFS_          _IOW('c', 4, uint32_t)
#define BC_ACQUIRE_          _IOW('c', 5, uint32_t)
#define BC_RELEASE_          _IOW('c', 6, uint32_t)
#define BC_DECREFS_          _IOW('c', 7, uint32_t)
#define BC_REGISTER_LOOPER_  _IO('c', 11)
#define BC_ENTER_LOOPER_     _IO('c', 12)
#define BC_EXIT_LOOPER_      _IO('c', 13)
#define BC_REQUEST_DEATH_    ((unsigned)(0x40000000u | (12u << 16) | ('c' << 8) | 14))
#define BC_CLEAR_DEATH_      ((unsigned)(0x40000000u | (12u << 16) | ('c' << 8) | 15))

#define BINDER_WRITE_READ_   _IOWR('b', 1, struct b_bwr)
#define BINDER_SET_MAX_THREADS_ _IOW('b', 5, uint32_t)
#define BINDER_SET_CONTEXT_MGR_ _IOW('b', 7, int32_t)
#define BINDER_THREAD_EXIT_  _IOW('b', 8, int32_t)
#define BINDER_VERSION_      _IOWR('b', 9, int32_t)
#define BINDER_SET_CONTEXT_MGR_EXT_ _IOW('b', 13, struct b_fbo)
#define BINDER_ONEWAY_SPAM_  _IOW('b', 16, uint32_t)

#define BMAXPROC 48
#define BMAXTHR 256
#define BMAXNODE 2048
#define BMAXREF 1024
#define BMAXBUF 1024

struct bproc; struct bthr; struct btxn;
struct bwork { struct bwork *next; uint32_t cmd; uint64_t a, b; struct btxn *tx; };
struct bq { struct bwork *head, *tail; };
struct bnode { struct bproc *owner; uint64_t ptr, cookie; uint32_t flags; int dead, used; };
struct bref { struct bnode *node; uint64_t death; };
struct bbuf { uint64_t off, size; int used; };
struct btxn {
    struct bthr *from; struct bproc *to; uint32_t code, flags; int32_t spid; uint64_t buf, offs, dsz, osz;
    struct bnode *node; struct btxn *next_serving; int oneway, isreply;
    int nfix; struct { uint64_t addr; int sfd; } *fix;      /* fds to open in the receiver when the transaction is delivered */
};
struct bproc {
    int used; pid_t tgid; int ctx; unsigned long base, size; int nthreads_traced;
    struct bq todo; struct bref refs[BMAXREF]; int nrefs; struct bbuf bufs[BMAXBUF];
    unsigned max_threads, requested, started; int fifo_fd; char fifo_path[PATH_MAX]; int ready; int dead;
};
struct bthr {
    int used; pid_t tid; struct bproc *p; struct bq todo; int looper, waiting, nwait; unsigned long bwr_addr; struct b_bwr bwr;
    struct btxn *serving; struct tp *tp;
};
struct bfd { int used; pid_t tgid; int fd; struct bproc *p; };

static struct bproc BP[BMAXPROC];
static struct bthr BTH[BMAXTHR];
static struct bnode BND[BMAXNODE];
static struct bnode *B_CTXMGR[3];
static struct bfd BFD[BMAXPROC * 4];
static char BDIR[PATH_MAX]; static int BSEQ, BDEBUG, BINDER_ON = 1;

static void blog(const char *f, ...) {
    if (!BDEBUG) return;
    char b[400]; va_list ap; va_start(ap, f); vsnprintf(b, sizeof b, f, ap); va_end(ap); dprintf(2, "[binder] %s\n", b);
}

/* ---------------------------------------------------------------- queues */
static void bpush(struct bq *q, uint32_t cmd, uint64_t a, uint64_t b, struct btxn *tx) {
    struct bwork *w = calloc(1, sizeof *w); if (!w) return;
    w->cmd = cmd; w->a = a; w->b = b; w->tx = tx;
    if (q->tail) q->tail->next = w; else q->head = w;
    q->tail = w;
}
static struct bwork *bpop(struct bq *q) {
    struct bwork *w = q->head; if (!w) return NULL;
    q->head = w->next; if (!q->head) q->tail = NULL; w->next = NULL; return w;
}

/* ---------------------------------------------------------------- process / thread lookup */
static pid_t b_tgid_of(pid_t tid) {
    char p[64], l[256]; snprintf(p, sizeof p, "/proc/%d/status", tid);
    FILE *f = fopen(p, "r"); if (!f) return tid;
    pid_t r = tid;
    while (fgets(l, sizeof l, f)) if (!strncmp(l, "Tgid:", 5)) { r = atoi(l + 5); break; }
    fclose(f); return r;
}
static struct bproc *bproc_get(pid_t tgid, int ctx, int create) {
    for (int i = 0; i < BMAXPROC; i++) if (BP[i].used && !BP[i].dead && BP[i].tgid == tgid && BP[i].ctx == ctx) return &BP[i];
    if (!create) return NULL;
    for (int i = 0; i < BMAXPROC; i++) if (!BP[i].used) {
        memset(&BP[i], 0, sizeof BP[i]); BP[i].used = 1; BP[i].tgid = tgid; BP[i].ctx = ctx; BP[i].nrefs = 1; BP[i].fifo_fd = -1; return &BP[i];
    }
    return NULL;
}
static struct bproc *bproc_any(pid_t tgid) {
    for (int i = 0; i < BMAXPROC; i++) if (BP[i].used && !BP[i].dead && BP[i].tgid == tgid) return &BP[i];
    return NULL;
}
static struct bthr *bthr_get(struct bproc *p, pid_t tid, int create) {
    for (int i = 0; i < BMAXTHR; i++) if (BTH[i].used && BTH[i].tid == tid && BTH[i].p == p) return &BTH[i];
    if (!create) return NULL;
    for (int i = 0; i < BMAXTHR; i++) if (!BTH[i].used) { memset(&BTH[i], 0, sizeof BTH[i]); BTH[i].used = 1; BTH[i].tid = tid; BTH[i].p = p; BTH[i].tp = tget(tid, 0); return &BTH[i]; }
    return NULL;
}
/* any live thread of the process that we can use for process_vm_* / /proc/pid/mem */
static pid_t bproc_tid(struct bproc *p) {
    for (int i = 0; i < BMAXTHR; i++) if (BTH[i].used && BTH[i].p == p) return BTH[i].tid;
    return p->tgid;
}

/* ---------------------------------------------------------------- nodes and references */
static struct bnode *bnode_find(struct bproc *p, uint64_t ptr) {
    for (int i = 0; i < BMAXNODE; i++) if (BND[i].used && BND[i].owner == p && BND[i].ptr == ptr && !BND[i].dead) return &BND[i];
    return NULL;
}
static struct bnode *bnode_new(struct bproc *p, uint64_t ptr, uint64_t cookie, uint32_t flags) {
    for (int i = 0; i < BMAXNODE; i++) if (!BND[i].used) { BND[i].used = 1; BND[i].owner = p; BND[i].ptr = ptr; BND[i].cookie = cookie; BND[i].flags = flags; BND[i].dead = 0; return &BND[i]; }
    return NULL;
}
/* returns the handle the process `to` uses for node (creating it; the owner is told to keep the object alive) */
static int bref_for(struct bproc *to, struct bnode *n, struct bq *ownerq) {
    for (int h = 1; h < to->nrefs; h++) if (to->refs[h].node == n) return h;
    if (to->nrefs >= BMAXREF) return -1;
    int h = to->nrefs++; to->refs[h].node = n; to->refs[h].death = 0;
    /* The context manager node (ptr 0, cookie 0) is created by the driver already holding its own refs: the kernel never sends BR_INCREFS/BR_ACQUIRE for it,
     * and libbinder would call incStrong() on a NULL object (SIGSEGV in RefBase::incStrong from IPCThreadState::executeCommand). */
    /* ownerq: where the owner is told. When the owner itself is the sender (it is handing out its own object) the kernel queues BR_INCREFS/BR_ACQUIRE on the
     * SENDER THREAD's todo list, so they are read before the transaction returns. Queued on the process instead, they arrive after the sender dropped its
     * temporary strong pointer (e.g. installd's `new Installd` passed to addService): the object is already freed and incStrong() crashes (fault_addr 0x4). */
    if (n->owner != to && (n->ptr || n->cookie)) { struct bq *q = ownerq ? ownerq : &n->owner->todo; bpush(q, BR_INCREFS_, n->ptr, n->cookie, NULL); bpush(q, BR_ACQUIRE_, n->ptr, n->cookie, NULL); }
    return h;
}
static struct bnode *bnode_of_handle(struct bproc *p, uint32_t h) {
    if (h == 0) { struct bnode *n = B_CTXMGR[p->ctx]; return (n && !n->dead) ? n : NULL; }
    if (h >= (uint32_t)p->nrefs) return NULL;
    return p->refs[h].node;
}

/* ---------------------------------------------------------------- transaction buffers inside the target's binder mmap */
static int balloc(struct bproc *p, uint64_t size, uint64_t *off) {
    size = (size + 7) & ~7ULL; if (!size) size = 8;
    uint64_t cand[BMAXBUF + 1]; int nc = 0; cand[nc++] = 0;
    for (int i = 0; i < BMAXBUF; i++) if (p->bufs[i].used) cand[nc++] = p->bufs[i].off + p->bufs[i].size;
    uint64_t best = (uint64_t)-1;
    for (int c = 0; c < nc; c++) {
        uint64_t s = cand[c]; if (s + size > p->size || s >= best) continue;
        int ok = 1; for (int i = 0; i < BMAXBUF; i++) if (p->bufs[i].used && s < p->bufs[i].off + p->bufs[i].size && p->bufs[i].off < s + size) { ok = 0; break; }
        if (ok) best = s;
    }
    if (best == (uint64_t)-1) return -1;
    for (int i = 0; i < BMAXBUF; i++) if (!p->bufs[i].used) { p->bufs[i].used = 1; p->bufs[i].off = best; p->bufs[i].size = size; *off = best; return 0; }
    return -1;
}
static void bfree(struct bproc *p, uint64_t addr) {
    if (addr < p->base) return; uint64_t off = addr - p->base;
    for (int i = 0; i < BMAXBUF; i++) if (p->bufs[i].used && p->bufs[i].off == off) { p->bufs[i].used = 0; return; }
}

/* ---------------------------------------------------------------- readiness (epoll/poll on the guest's binder fd) */
static int bproc_ready(struct bproc *p) {
    if (p->todo.head) return 1;
    for (int i = 0; i < BMAXTHR; i++) if (BTH[i].used && BTH[i].p == p && !BTH[i].waiting && BTH[i].todo.head) return 1;
    return 0;
}
static void bsync(struct bproc *p) {
    if (p->fifo_fd < 0) return;
    int r = bproc_ready(p);
    if (r && !p->ready) { char c = 1; if (write(p->fifo_fd, &c, 1) == 1) p->ready = 1; }
    else if (!r && p->ready) { char c; if (read(p->fifo_fd, &c, 1) >= 0) p->ready = 0; }
}

/* ---------------------------------------------------------------- filling the guest's read buffer */
static int bthr_busy(struct bthr *th) { return th->serving != NULL || th->nwait > 0; }
static int bhas_work(struct bthr *th) { return th->todo.head || (th->looper && !bthr_busy(th) && th->p->todo.head); }
static int bproc_has_idle_looper(struct bproc *p) {
    for (int i = 0; i < BMAXTHR; i++) if (BTH[i].used && BTH[i].p == p && BTH[i].waiting && BTH[i].looper && !bthr_busy(&BTH[i])) return 1;
    return 0;
}
static int bput(struct bthr *th, const void *src, size_t n) {   /* append to the guest's read buffer */
    struct b_bwr *w = &th->bwr; if (w->read_consumed + n > w->read_size) return -1;
    if (wr(th->tid, w->read_buffer + w->read_consumed, src, n)) return -1;
    w->read_consumed += n; return 0;
}
static size_t bpayload(uint32_t cmd) { return _IOC_SIZE(cmd); }


/* ---------------------------------------------------------------- file descriptors
 * The sender's fd is duplicated into the supervisor (pidfd_getfd, or /proc/PID/fd/N), and when the transaction is
 * delivered the receiving thread - stopped at the entry of its binder ioctl - is made to run open("/proc/SUPERVISOR/fd/M")
 * by rewriting its registers (the usual ptrace syscall-injection trick). */
static int b_grab_fd(pid_t tid, pid_t tgid, int fd) {
    (void)tgid;     /* pidfd_getfd is not allowed by Android's app seccomp filter: duplicate through /proc instead (files, pipes, memfd, ...) */
    char p[64]; snprintf(p, sizeof p, "/proc/%d/fd/%d", tid, fd);
    int s = open(p, O_RDWR | O_CLOEXEC); if (s < 0) s = open(p, O_RDONLY | O_CLOEXEC);
    return s;
}
static long b_inject_open(pid_t tid, const char *path, int flags) {
    regs_t orig, r; if (get_regs(tid, &orig)) return -EIO; r = orig;
    unsigned long dst = (R_SP(&orig) - 256 - 8192 - 4096) & ~15UL;
    if (wr(tid, dst, path, strlen(path) + 1)) return -EFAULT;
    *ARG(&r, 0) = (unsigned long)-100; *ARG(&r, 1) = dst; *ARG(&r, 2) = (unsigned long)flags; *ARG(&r, 3) = 0;
#if defined(__aarch64__)
    if (set_regs(tid, &r)) return -EIO;
    { int nr = (int)SYS_openat; struct iovec v = { &nr, sizeof nr }; if (ptrace(PTRACE_SETREGSET, tid, (void *)NT_ARM_SYSTEM_CALL, &v)) return -EIO; }
#else
    R_NR(&r) = (unsigned long)SYS_openat; if (set_regs(tid, &r)) return -EIO;
#endif
    if (ptrace(PTRACE_SYSCALL, tid, 0, 0)) return -EIO;
    for (int guard = 0; guard < 50; guard++) {
        int st; pid_t w = waitpid(tid, &st, __WALL); if (w < 0) return -ECHILD;
        if (!WIFSTOPPED(st)) return -ESRCH;
        if (WSTOPSIG(st) == (SIGTRAP | 0x80)) { regs_t x; if (get_regs(tid, &x)) return -EIO; return (long)R_RET(&x); }
        ptrace(PTRACE_SYSCALL, tid, 0, (WSTOPSIG(st) == SIGSTOP || WSTOPSIG(st) == SIGTRAP) ? 0 : (void *)(long)WSTOPSIG(st));
    }
    return -EIO;
}
/* After injecting we are at the EXIT stop of the injected call: turn that into the (emulated) ioctl's exit with `ret`. */
static void b_finish_exit(struct tp *t, pid_t tid, long ret) {
    regs_t r; if (!get_regs(tid, &r)) { R_RET(&r) = (unsigned long)ret; set_regs(tid, &r); }
    t->in_sys = 0; t->bemu = 0; t->deferred = 0; t->touched = 0;
}
static void b_deliver_fds(struct bthr *th, struct btxn *tx) {
    if (!tx->nfix || !th->tp) return;
    for (int i = 0; i < tx->nfix; i++) {
        char p[96]; snprintf(p, sizeof p, "/proc/%d/fd/%d", (int)getpid(), tx->fix[i].sfd);
        long fd = b_inject_open(th->tid, p, O_RDWR | O_CLOEXEC);
        if (fd < 0) fd = b_inject_open(th->tid, p, O_RDONLY | O_CLOEXEC);
        blog("fd passed to tid %d: supervisor fd %d -> guest fd %ld", th->tid, tx->fix[i].sfd, fd);
        int32_t v = fd < 0 ? -1 : (int32_t)fd; wr(th->tid, tx->fix[i].addr, &v, 4);
        close(tx->fix[i].sfd);
    }
    free(tx->fix); tx->fix = NULL; tx->nfix = 0; th->tp->binjected = 1;
}

/* Moves pending work into the read buffer. Caller checked bhas_work(). */
static void bfill(struct bthr *th) {
    struct b_bwr *w = &th->bwr;
    if (w->read_consumed == 0) { uint32_t c = BR_NOOP_; bput(th, &c, 4); }
    for (;;) {
        struct bwork *k = th->todo.head ? th->todo.head : ((th->looper && !bthr_busy(th)) ? th->p->todo.head : NULL);
        if (!k) break;
        size_t need = 4 + bpayload(k->cmd);
        if (w->read_consumed + need > w->read_size) break;
        k = th->todo.head ? bpop(&th->todo) : bpop(&th->p->todo);
        uint32_t c = k->cmd; bput(th, &c, 4);
        if (c == BR_TRANSACTION_ || c == BR_REPLY_) {
            struct btxn *tx = k->tx; struct b_tr tr; memset(&tr, 0, sizeof tr);
            if (c == BR_TRANSACTION_ && tx->node) { tr.target = tx->node->ptr; tr.cookie = tx->node->cookie; }
            tr.code = tx->code; tr.flags = tx->flags; tr.pid = tx->spid; tr.euid = 0;
            tr.data_size = tx->dsz; tr.offsets_size = tx->osz; tr.buffer = tx->buf; tr.offsets = tx->offs;
            bput(th, &tr, sizeof tr);
            if (tx->nfix) b_deliver_fds(th, tx);
            if (c == BR_TRANSACTION_ && !tx->oneway) { tx->next_serving = th->serving; th->serving = tx; }
            else if (c == BR_REPLY_) { if (th->nwait > 0) th->nwait--; }
        } else if (c == BR_INCREFS_ || c == BR_ACQUIRE_) { struct b_pc pc = { k->a, k->b }; bput(th, &pc, sizeof pc); }
        else if (c == BR_DEAD_BINDER_ || c == BR_CLEAR_DEATH_NOTIFICATION_DONE_) { uint64_t ck = k->a; bput(th, &ck, 8); }
        else if (c == BR_DEAD_REPLY_ || c == BR_FAILED_REPLY_) { if (th->nwait > 0) th->nwait--; }
        free(k);
    }
    /* ask for another pool thread when everyone is busy */
    struct bproc *p = th->p;
    if (th->looper && p->requested == 0 && p->started < p->max_threads && !bproc_has_idle_looper(p)) {
        uint32_t c = BR_SPAWN_LOOPER_; if (!bput(th, &c, 4)) p->requested++;
    }
}

/* Completes a deferred BINDER_WRITE_READ of a waiting thread. */
static void bwake(struct bthr *th) {
    if (!th->waiting || !bhas_work(th)) return;
    if (th->tp) th->tp->binjected = 0;
    bfill(th);
    wr(th->tid, th->bwr_addr + 32, &th->bwr.read_consumed, 8);      /* read_consumed field */
    th->waiting = 0;
    if (th->tp && th->tp->binjected) { b_finish_exit(th->tp, th->tid, 0); th->tp->binjected = 0; blog("wake+fds tid=%d", th->tid); ptrace(PTRACE_SYSCALL, th->tid, 0, 0); return; }
    if (th->tp) { th->tp->deferred = 0; th->tp->bret = 0; }
    blog("wake tid=%d consumed=%llu", th->tid, (unsigned long long)th->bwr.read_consumed);
    ptrace(PTRACE_SYSCALL, th->tid, 0, 0);
}
static void bkick_proc(struct bproc *p) {
    for (int i = 0; i < BMAXTHR; i++) if (BTH[i].used && BTH[i].p == p && BTH[i].waiting && bhas_work(&BTH[i])) { bwake(&BTH[i]); if (p->todo.head == NULL) break; }
    bsync(p);
}

/* ---------------------------------------------------------------- transactions */
struct b_pinfo { int is_ptr; uint64_t off, len; };
static int b_addfix(struct btxn *tx, uint64_t addr, int sfd) {
    void *n = realloc(tx->fix, (tx->nfix + 1) * sizeof tx->fix[0]); if (!n) return -1;
    tx->fix = n; tx->fix[tx->nfix].addr = addr; tx->fix[tx->nfix].sfd = sfd; tx->nfix++; return 0;
}
/* tbuf is the complete image of the target allocation: data | offsets | scatter-gather buffers; base is its address in the target. */
static int b_translate(struct bthr *from, struct bproc *to, struct btxn *tx, unsigned char *tbuf, uint64_t base, size_t dsz,
                       const uint64_t *offs, size_t noffs, uint64_t sgstart, uint64_t sgend) {
    struct b_pinfo *pi = calloc(noffs ? noffs : 1, sizeof *pi); if (!pi) return -1;
    uint64_t cur = sgstart; int rc = 0;
    for (size_t i = 0; i < noffs && !rc; i++) {
        uint64_t off = offs[i]; if (off + 8 > dsz) { rc = -1; break; }
        uint32_t type; memcpy(&type, tbuf + off, 4);
        if (type == BT_BINDER || type == BT_WBINDER) {
            struct b_fbo *o = (struct b_fbo *)(tbuf + off); if (off + sizeof *o > dsz) { rc = -1; break; }
            struct bnode *n = bnode_find(from->p, o->binder); if (!n) n = bnode_new(from->p, o->binder, o->cookie, o->flags); if (!n) { rc = -1; break; }
            if (to == from->p) continue;
            int h = bref_for(to, n, &from->todo); if (h < 0) { rc = -1; break; }
            o->type = type == BT_BINDER ? BT_HANDLE : BT_WHANDLE; o->binder = (uint64_t)h; o->cookie = 0;
        } else if (type == BT_HANDLE || type == BT_WHANDLE) {
            struct b_fbo *o = (struct b_fbo *)(tbuf + off); if (off + sizeof *o > dsz) { rc = -1; break; }
            struct bnode *n = bnode_of_handle(from->p, (uint32_t)o->binder); if (!n) { rc = -2; break; }
            if (n->owner == to) { o->type = type == BT_HANDLE ? BT_BINDER : BT_WBINDER; o->binder = n->ptr; o->cookie = n->cookie; }
            else { int h = bref_for(to, n, NULL); if (h < 0) { rc = -1; break; } o->binder = (uint64_t)h; o->cookie = 0; }
        } else if (type == BT_FD) {
            struct b_fbo *o = (struct b_fbo *)(tbuf + off); if (off + sizeof *o > dsz) { rc = -1; break; }
            int fd = (int)(uint32_t)o->binder; int sfd = b_grab_fd(from->tid, from->p->tgid, fd);
            if (sfd < 0) { blog("cannot duplicate sender fd %d (tid %d)", fd, from->tid); o->binder = 0xffffffffu; continue; }
            if (b_addfix(tx, base + off + 8, sfd)) { close(sfd); rc = -1; break; }
            o->binder = 0xffffffffu;                      /* patched when the receiver reads the transaction */
        } else if (type == BT_PTR) {
            struct b_bufobj *o = (struct b_bufobj *)(tbuf + off); if (off + sizeof *o > dsz) { rc = -1; break; }
            uint64_t len = o->length, al = (len + 7) & ~7ULL;
            if (cur + al > sgend) { blog("scatter-gather buffer overflow"); rc = -1; break; }
            if (len && rd(from->tid, o->buffer, tbuf + cur, len)) { blog("cannot read sg buffer from tid %d", from->tid); rc = -1; break; }
            pi[i].is_ptr = 1; pi[i].off = cur; pi[i].len = len;
            o->buffer = base + cur; cur += al;
            if (o->flags & 1 /*HAS_PARENT*/) {
                uint64_t par = o->parent, po = o->parent_offset;
                if (par >= i || !pi[par].is_ptr || po + 8 > pi[par].len) { blog("bad sg parent"); rc = -1; break; }
                memcpy(tbuf + pi[par].off + po, &o->buffer, 8);
            }
        } else if (type == BT_FDA) {
            struct b_fdaobj *o = (struct b_fdaobj *)(tbuf + off); if (off + sizeof *o > dsz) { rc = -1; break; }
            uint64_t par = o->parent, po = o->parent_offset, n = o->num_fds;
            if (par >= i || !pi[par].is_ptr || po + n * 4 > pi[par].len) { blog("bad fd array"); rc = -1; break; }
            for (uint64_t k = 0; k < n && !rc; k++) {
                uint32_t fd; memcpy(&fd, tbuf + pi[par].off + po + k * 4, 4);
                int sfd = b_grab_fd(from->tid, from->p->tgid, (int)fd); uint32_t neg = 0xffffffffu;
                memcpy(tbuf + pi[par].off + po + k * 4, &neg, 4);
                if (sfd < 0) { blog("cannot duplicate sender fd %u (fd array)", fd); continue; }
                if (b_addfix(tx, base + pi[par].off + po + k * 4, sfd)) { close(sfd); rc = -1; }
            }
        }
    }
    free(pi); return rc;
}

static void b_dead_reply(struct bthr *to_thr, uint32_t cmd) {
    if (!to_thr || !to_thr->used) return;
    bpush(&to_thr->todo, cmd, 0, 0, NULL);
    if (to_thr->waiting) bwake(to_thr);
    bsync(to_thr->p);
}

static void b_fail(struct bthr *th, uint32_t cmd) { bpush(&th->todo, cmd, 0, 0, NULL); }
static void b_txn(struct bthr *th, const struct b_tr *tr, int reply, uint64_t sgsize) {
    struct bproc *to = NULL; struct bthr *to_thr = NULL; struct bnode *node = NULL;
    int oneway = !reply && (tr->flags & 1);
    if (reply) {
        struct btxn *in = th->serving;
        if (!in) { blog("reply without a transaction (tid %d)", th->tid); b_fail(th, BR_FAILED_REPLY_); return; }
        th->serving = in->next_serving;
        to_thr = in->from; if (!to_thr || !to_thr->used) { bpush(&th->todo, BR_TRANSACTION_COMPLETE_, 0, 0, NULL); return; }
        to = to_thr->p;
    } else {
        node = bnode_of_handle(th->p, (uint32_t)tr->target);
        if (!node) { b_fail(th, (uint32_t)tr->target == 0 ? BR_DEAD_REPLY_ : BR_FAILED_REPLY_); return; }
        if (node->dead || !node->owner || node->owner->dead) { b_fail(th, BR_DEAD_REPLY_); return; }
        to = node->owner;
        if (!oneway) for (struct btxn *s = th->serving; s; s = s->next_serving) if (s->from && s->from->used && s->from->p == to) { to_thr = s->from; break; }
    }
    uint64_t dsz = tr->data_size, osz = tr->offsets_size;
    if (osz % 8 || dsz > (1u << 22) || osz > (1u << 20) || sgsize > (1u << 22) || !to->size) { b_fail(th, BR_FAILED_REPLY_); return; }
    uint64_t dal = (dsz + 7) & ~7ULL, oal = (osz + 7) & ~7ULL, sal = (sgsize + 7) & ~7ULL, total = dal + oal + sal;
    unsigned char *tbuf = calloc(1, total + 8); uint64_t *offs = malloc(osz + 8);
    if (!tbuf || !offs) { free(tbuf); free(offs); b_fail(th, BR_FAILED_REPLY_); return; }
    if ((dsz && rd(th->tid, tr->buffer, tbuf, dsz)) || (osz && rd(th->tid, tr->offsets, offs, osz))) { blog("cannot read txn data from tid %d", th->tid); free(tbuf); free(offs); b_fail(th, BR_FAILED_REPLY_); return; }
    uint64_t aoff; if (balloc(to, total, &aoff)) { blog("no buffer space in pid %d", to->tgid); free(tbuf); free(offs); b_fail(th, BR_FAILED_REPLY_); return; }
    uint64_t baddr = to->base + aoff, oaddr = baddr + dal;
    struct btxn *tx = calloc(1, sizeof *tx); if (!tx) { free(tbuf); free(offs); bfree(to, baddr); b_fail(th, BR_FAILED_REPLY_); return; }
    int rc = b_translate(th, to, tx, tbuf, baddr, dsz, offs, osz / 8, dal + oal, dal + oal + sal);
    if (rc) { blog("translate failed rc=%d tid=%d", rc, th->tid); for (int i = 0; i < tx->nfix; i++) close(tx->fix[i].sfd); free(tx->fix); free(tx); free(tbuf); free(offs); bfree(to, baddr); b_fail(th, BR_FAILED_REPLY_); return; }
    if (osz) memcpy(tbuf + dal, offs, osz);
    pid_t ttid = bproc_tid(to);
    if (wr(ttid, baddr, tbuf, total)) { blog("cannot write txn data into pid %d (tid %d) at %llx", to->tgid, ttid, (unsigned long long)baddr); for (int i = 0; i < tx->nfix; i++) close(tx->fix[i].sfd); free(tx->fix); free(tx); free(tbuf); free(offs); bfree(to, baddr); b_fail(th, BR_FAILED_REPLY_); return; }
    free(tbuf); free(offs);
    tx->from = th; tx->to = to; tx->code = tr->code; tx->flags = tr->flags; tx->spid = th->p->tgid; tx->buf = baddr; tx->offs = oaddr;
    tx->dsz = dsz; tx->osz = osz; tx->node = node; tx->oneway = oneway; tx->isreply = reply;
    blog("%s tid=%d -> pid=%d code=%u data=%llu offs=%llu sg=%llu fds=%d%s", reply ? "REPLY" : "TXN", th->tid, to->tgid, tr->code, (unsigned long long)dsz, (unsigned long long)osz, (unsigned long long)sgsize, tx->nfix, to_thr ? " (thread)" : "");
    if (reply) {
        bpush(&to_thr->todo, BR_REPLY_, 0, 0, tx);
        bpush(&th->todo, BR_TRANSACTION_COMPLETE_, 0, 0, NULL);
        if (to_thr->waiting) bwake(to_thr);
        bsync(to);
    } else {
        bpush(&th->todo, BR_TRANSACTION_COMPLETE_, 0, 0, NULL);
        if (!oneway) th->nwait++;
        if (to_thr) { bpush(&to_thr->todo, BR_TRANSACTION_, 0, 0, tx); if (to_thr->waiting) bwake(to_thr); bsync(to); }
        else { bpush(&to->todo, BR_TRANSACTION_, 0, 0, tx); bkick_proc(to); }
    }
}

/* ---------------------------------------------------------------- death */
static void bproc_dead(struct bproc *p);
static void bthr_free(struct bthr *th) {
    if (!th->used) return;
    for (struct btxn *s = th->serving; s; s = s->next_serving) if (s->from) b_dead_reply(s->from, BR_DEAD_REPLY_);
    struct bwork *k; while ((k = bpop(&th->todo))) { if (k->tx && !k->tx->oneway && k->cmd == BR_TRANSACTION_ && k->tx->from) b_dead_reply(k->tx->from, BR_DEAD_REPLY_); free(k); }
    for (int i = 0; i < BMAXTHR; i++) if (BTH[i].used) for (struct btxn *s = BTH[i].serving; s; s = s->next_serving) if (s->from == th) s->from = NULL;
    th->used = 0;
}
static void bproc_dead(struct bproc *p) {
    if (!p->used || p->dead) return;
    blog("process %d (ctx %d) died", p->tgid, p->ctx);
    p->dead = 1;
    for (int i = 0; i < BMAXTHR; i++) if (BTH[i].used && BTH[i].p == p) bthr_free(&BTH[i]);
    struct bwork *k; while ((k = bpop(&p->todo))) { if (k->tx && !k->tx->oneway && k->cmd == BR_TRANSACTION_ && k->tx->from) b_dead_reply(k->tx->from, BR_DEAD_REPLY_); free(k); }
    for (int i = 0; i < BMAXNODE; i++) if (BND[i].used && BND[i].owner == p) {
        BND[i].dead = 1; for (int c = 0; c < 3; c++) if (B_CTXMGR[c] == &BND[i]) B_CTXMGR[c] = NULL;
        for (int q = 0; q < BMAXPROC; q++) if (BP[q].used && !BP[q].dead && &BP[q] != p)
            for (int h = 1; h < BP[q].nrefs; h++) if (BP[q].refs[h].node == &BND[i] && BP[q].refs[h].death) {
                bpush(&BP[q].todo, BR_DEAD_BINDER_, BP[q].refs[h].death, 0, NULL); BP[q].refs[h].death = 0; bkick_proc(&BP[q]);
            }
    }
    if (p->fifo_fd >= 0) { close(p->fifo_fd); p->fifo_fd = -1; }
    for (int i = 0; i < (int)(sizeof BFD / sizeof BFD[0]); i++) if (BFD[i].used && BFD[i].p == p) BFD[i].used = 0;
}
/* a traced thread/process is gone */
static void b_thread_gone(pid_t tid, pid_t tgid, int group_empty) {
    for (int i = 0; i < BMAXTHR; i++) if (BTH[i].used && BTH[i].tid == tid) bthr_free(&BTH[i]);
    if (group_empty) for (int i = 0; i < BMAXPROC; i++) if (BP[i].used && !BP[i].dead && BP[i].tgid == tgid) bproc_dead(&BP[i]);
}

/* ---------------------------------------------------------------- command stream (guest -> driver) */
static void b_write(struct bthr *th, uint64_t buf, uint64_t size, uint64_t *consumed) {
    uint64_t pos = 0;
    while (pos + 4 <= size) {
        uint32_t cmd; if (rd(th->tid, buf + pos, &cmd, 4)) break;
        size_t sz = _IOC_SIZE(cmd); if (pos + 4 + sz > size) break;
        unsigned char arg[80]; if (sz > sizeof arg) break;
        if (sz && rd(th->tid, buf + pos + 4, arg, sz)) break;
        pos += 4 + sz;
        if (cmd == BC_TRANSACTION_ || cmd == BC_REPLY_) { struct b_tr tr; memcpy(&tr, arg, sizeof tr); b_txn(th, &tr, cmd == BC_REPLY_, 0); }
        else if (cmd == BC_TRANSACTION_SG_ || cmd == BC_REPLY_SG_) { struct b_tr_sg sg; memcpy(&sg, arg, sizeof sg); b_txn(th, &sg.tr, cmd == BC_REPLY_SG_, sg.buffers_size); }
        else if (cmd == BC_FREE_BUFFER_) { uint64_t a; memcpy(&a, arg, 8); bfree(th->p, a); }
        else if (cmd == BC_INCREFS_ || cmd == BC_ACQUIRE_ || cmd == BC_RELEASE_ || cmd == BC_DECREFS_) { /* counts are not tracked: objects live until their process dies */ }
        else if (cmd == BC_REGISTER_LOOPER_) { if (th->p->requested) th->p->requested--; th->p->started++; th->looper = 1; }
        else if (cmd == BC_ENTER_LOOPER_) th->looper = 1;
        else if (cmd == BC_EXIT_LOOPER_) th->looper = 0;
        else if (cmd == BC_REQUEST_DEATH_) { uint32_t h; uint64_t ck; memcpy(&h, arg, 4); memcpy(&ck, arg + 4, 8); if (h && h < (uint32_t)th->p->nrefs) { struct bnode *n = th->p->refs[h].node;
                if (n && n->dead) bpush(&th->p->todo, BR_DEAD_BINDER_, ck, 0, NULL); else th->p->refs[h].death = ck; } }
        else if (cmd == BC_CLEAR_DEATH_) { uint32_t h; uint64_t ck; memcpy(&h, arg, 4); memcpy(&ck, arg + 4, 8); if (h && h < (uint32_t)th->p->nrefs) th->p->refs[h].death = 0; bpush(&th->todo, BR_CLEAR_DEATH_NOTIFICATION_DONE_, ck, 0, NULL); }
        else if (_IOC_TYPE(cmd) == 'c') { /* *_DONE, DEAD_BINDER_DONE, ACQUIRE_RESULT ...: nothing to do */ }
        else { blog("unknown command %08x", cmd); break; }
    }
    *consumed = pos;
}

/* ---------------------------------------------------------------- guest syscalls */
static struct bfd *bfd_find(pid_t tgid, int fd) {
    for (int i = 0; i < (int)(sizeof BFD / sizeof BFD[0]); i++) if (BFD[i].used && BFD[i].tgid == tgid && BFD[i].fd == fd) return &BFD[i];
    return NULL;
}
static int b_dev_of(const char *g) {
    if (!strcmp(g, "/dev/binder")) return 0; if (!strcmp(g, "/dev/hwbinder")) return 1; if (!strcmp(g, "/dev/vndbinder")) return 2; return -1;
}

/* Called at syscall entry. Returns 1 if the syscall was taken over (t->bemu set; result in t->bret; t->deferred if blocking). */
static int bnd_enter(pid_t pid, struct tp *t, regs_t *r) {
    long nr = t->nr;
    if (!t->tgid) t->tgid = b_tgid_of(pid);
    /* open("/dev/binder") */
    int pidx = -1;
    if (nr == SYS_openat) pidx = 1;
#ifdef SYS_open
    else if (nr == SYS_open) pidx = 0;
#endif
    if (pidx >= 0) {
        char g[PATH_MAX]; if (rd_str(pid, *ARG(r, pidx), g, sizeof g)) return 0;
        int dev = b_dev_of(g); if (dev < 0 || !BDIR[0]) return 0;
        char fp[PATH_MAX]; snprintf(fp, sizeof fp, "%s/%d", BDIR, ++BSEQ);
        unlink(fp); if (mkfifo(fp, 0600)) return 0;
        int sup = open(fp, O_RDWR | O_NONBLOCK | O_CLOEXEC); if (sup < 0) { unlink(fp); return 0; }
        unsigned long dst = (R_SP(r) - 256 - 8192) & ~15UL; if (wr(pid, dst, fp, strlen(fp) + 1)) { close(sup); unlink(fp); return 0; }
        *ARG(r, pidx) = dst; t->touched |= 1UL << pidx; set_regs(pid, r);
        t->bopen_dev = dev; t->bopen_sup = sup; snprintf(t->bopen_path, sizeof t->bopen_path, "%s", fp);
        return 1;                       /* the (rewritten) open runs for real (not skipped); on_exit registers the fd */
    }
    if (nr == SYS_close) {
        int fd = (int)*ARG(r, 0); struct bfd *b = bfd_find(t->tgid, fd);
        if (b) { t->bclose = fd + 1; }
        return 0;
    }
    if (nr == SYS_mmap) {
        struct bfd *b = bfd_find(t->tgid, (int)*ARG(r, 4)); if (!b) return 0;
        *ARG(r, 3) = (*ARG(r, 3) & ~0x1UL /*MAP_SHARED*/ & ~0x2UL) | 0x2 /*MAP_PRIVATE*/ | 0x20 /*MAP_ANONYMOUS*/;
        *ARG(r, 4) = (unsigned long)-1; *ARG(r, 5) = 0; t->touched |= (1UL << 3) | (1UL << 4) | (1UL << 5);
        t->bmmap = b; t->bmmap_len = *ARG(r, 1); set_regs(pid, r); return 1;
    }
    if (nr == SYS_ioctl) {
        struct bfd *b = bfd_find(t->tgid, (int)*ARG(r, 0)); if (!b) return 0;
        unsigned cmd = (unsigned)*ARG(r, 1); unsigned long arg = *ARG(r, 2); struct bproc *p = b->p; long ret = 0;
        if (p->dead) { ret = -EBADF; goto done; }
        struct bthr *th = bthr_get(p, pid, 1); if (!th) { ret = -ENOMEM; goto done; }
        switch (cmd) {
        case BINDER_VERSION_: { int32_t v = 8; if (wr(pid, arg, &v, 4)) ret = -EFAULT; break; }
        case BINDER_SET_MAX_THREADS_: { uint32_t m; if (rd(pid, arg, &m, 4)) ret = -EFAULT; else p->max_threads = m; break; }
        case BINDER_SET_CONTEXT_MGR_: case BINDER_SET_CONTEXT_MGR_EXT_: {
            if (B_CTXMGR[p->ctx] && !B_CTXMGR[p->ctx]->dead) { ret = -EBUSY; break; }
            struct bnode *n = bnode_new(p, 0, 0, 0); if (!n) { ret = -ENOMEM; break; } B_CTXMGR[p->ctx] = n;
            blog("context manager (ctx %d) = pid %d", p->ctx, p->tgid); break; }
        case BINDER_THREAD_EXIT_: bthr_free(th); break;
        case BINDER_ONEWAY_SPAM_: break;
        case BINDER_WRITE_READ_: {
            struct b_bwr bw; if (rd(pid, arg, &bw, sizeof bw)) { ret = -EFAULT; break; }
            if (bw.write_size > bw.write_consumed) { uint64_t c = 0; b_write(th, bw.write_buffer + bw.write_consumed, bw.write_size - bw.write_consumed, &c); bw.write_consumed += c; }
            if (bw.read_size > bw.read_consumed) {
                th->bwr = bw; th->bwr_addr = arg; th->tp = t;
                if (bhas_work(th)) { th->waiting = 0; t->binjected = 0; bfill(th); bw = th->bwr; if (t->binjected) { wr(pid, arg, &bw, sizeof bw); bsync(p); b_finish_exit(t, pid, 0); t->binjected = 0; return 1; } }
                else { wr(pid, arg, &bw, sizeof bw); th->waiting = 1; t->deferred = 1; skip_syscall(pid, r); t->bemu = 1; bsync(p); return 1; }
            }
            wr(pid, arg, &bw, sizeof bw); bsync(p); break; }
        case 0x4004620c /* BINDER_FREEZE-ish / unknown 'b' */: default:
            ret = _IOC_TYPE(cmd) == 'b' ? -EINVAL : -ENOTTY; break;
        }
        bkick_proc(p);
    done:
        skip_syscall(pid, r); t->bemu = 1; t->bret = ret; return 1;
    }
    return 0;
}

/* Called at syscall exit for the calls above (open registers the fd, mmap records the region, close forgets it). */
static void bnd_exit(pid_t pid, struct tp *t, long ret) {
    long nr = t->nr; int pidx = -1;
    if (nr == SYS_openat) pidx = 1;
#ifdef SYS_open
    else if (nr == SYS_open) pidx = 0;
#endif
    if (pidx >= 0 && t->bopen_sup > 0) {
        unlink(t->bopen_path);
        if (ret >= 0) {
            struct bproc *p = bproc_get(t->tgid, t->bopen_dev, 1);
            struct bfd *slot = NULL; for (int i = 0; i < (int)(sizeof BFD / sizeof BFD[0]); i++) if (!BFD[i].used) { slot = &BFD[i]; break; }
            if (p && slot) {
                slot->used = 1; slot->tgid = t->tgid; slot->fd = (int)ret; slot->p = p;
                if (p->fifo_fd < 0) { p->fifo_fd = t->bopen_sup; snprintf(p->fifo_path, sizeof p->fifo_path, "%s", t->bopen_path); } else close(t->bopen_sup);
                blog("open binder dev=%d pid=%d fd=%ld", t->bopen_dev, t->tgid, ret);
            } else close(t->bopen_sup);
        } else close(t->bopen_sup);
        t->bopen_sup = 0; return;
    }
    if (nr == SYS_close && t->bclose > 0) {
        if (ret == 0) { struct bfd *b = bfd_find(t->tgid, t->bclose - 1); if (b) { struct bproc *p = b->p; b->used = 0; int left = 0; for (int i = 0; i < (int)(sizeof BFD / sizeof BFD[0]); i++) if (BFD[i].used && BFD[i].p == p) left = 1; if (!left) bproc_dead(p); } }
        t->bclose = 0; return;
    }
    if (nr == SYS_mmap && t->bmmap) {
        if (ret > 0 || ret < -4095) { struct bproc *p = t->bmmap->p; if (!p->size) { p->base = (unsigned long)ret; p->size = t->bmmap_len; blog("binder mmap pid=%d base=%lx size=%lx", p->tgid, p->base, p->size); } }
        t->bmmap = NULL;
    }
}
