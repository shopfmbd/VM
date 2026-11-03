/*
 * propsvc.h - the guest's property service (what init serves on /dev/socket/property_service).
 * Guest processes call __system_property_set() = connect + send a request; init validates it and writes the value into the
 * shared property area. Here a helper process of vmloader does that, writing straight into the same files the app generated
 * (<rootfs>/dev/__properties__/u:object_r:default_prop:s0), which every guest process maps read-only.
 * Wire format (protocol v2, "ro.property_service.version"=2): u32 cmd=0x00020001, u32 len + name, u32 len + value; reply: i32 0.
 */
#include <sys/mman.h>

#define PS_HDR 128
struct pa { unsigned char *m; size_t size; };

static uint32_t *pa_at(struct pa *a, uint32_t off) { return (uint32_t *)(a->m + PS_HDR + off); }
static uint32_t pa_alloc(struct pa *a, uint32_t n) {
    uint32_t used = *(uint32_t *)a->m, off = used; used += (n + 3) & ~3u;
    if (PS_HDR + (size_t)used > a->size) return 0;
    *(uint32_t *)a->m = used; return off;
}
static int pa_cmp(const char *x, uint32_t xl, const char *y, uint32_t yl) { if (xl < yl) return -1; if (xl > yl) return 1; return strncmp(x, y, xl); }
/* node layout: [0]=namelen [1]=prop [2]=left [3]=right [4]=children, name follows at +20 */
static uint32_t pa_walk(struct pa *a, const char *name, int create) {
    uint32_t node = 0; const char *p = name;
    for (;;) {
        const char *dot = strchr(p, '.'); uint32_t tl = dot ? (uint32_t)(dot - p) : (uint32_t)strlen(p);
        if (!tl) return (uint32_t)-1;
        uint32_t *slot = pa_at(a, node) + 4;                      /* children */
        for (;;) {
            if (!*slot) {
                if (!create) return (uint32_t)-1;
                uint32_t n = pa_alloc(a, 20 + tl + 1); if (!n) return (uint32_t)-1;
                *pa_at(a, n) = tl; memcpy((char *)pa_at(a, n) + 20, p, tl); ((char *)pa_at(a, n) + 20)[tl] = 0; *slot = n;
            }
            uint32_t bt = *slot, bl = *pa_at(a, bt); int c = pa_cmp(p, tl, (const char *)pa_at(a, bt) + 20, bl);
            if (c == 0) { node = bt; break; }
            slot = pa_at(a, bt) + (c < 0 ? 2 : 3);
        }
        if (!dot) return node;
        p = dot + 1;
    }
}
/* returns 0 ok, 0xB read-only, 1 error */
static int pa_set(struct pa *a, struct pa *serial, const char *name, const char *value) {
    uint32_t vl = (uint32_t)strlen(value), nl = (uint32_t)strlen(name);
    if (nl == 0 || nl >= 32 || vl >= 92) return 1;
    uint32_t node = pa_walk(a, name, 1); if (node == (uint32_t)-1) return 1;
    uint32_t *prop = pa_at(a, node) + 1;
    if (*prop) {
        if (!strncmp(name, "ro.", 3)) return 0xB;
        uint32_t *pi = pa_at(a, *prop); uint32_t ser = pi[0]; uint32_t oldlen = ser >> 24; if (oldlen > 91) oldlen = 91;
        memcpy((char *)pa_at(a, 20), (char *)(pi + 1), oldlen + 1);   /* dirty backup area: readers use it while the dirty bit is set */
        ser |= 1; __atomic_store_n(&pi[0], ser, __ATOMIC_RELEASE);    /* dirty */
        memset((char *)(pi + 1), 0, 92); memcpy((char *)(pi + 1), value, vl);
        __atomic_store_n(&pi[0], (vl << 24) | ((ser + 1) & 0xffffff), __ATOMIC_RELEASE);
    } else {
        uint32_t off = pa_alloc(a, 96 + nl + 1); if (!off) return 1;
        uint32_t *pi = pa_at(a, off); pi[0] = vl << 24; memcpy((char *)(pi + 1), value, vl); memcpy((char *)pi + 96, name, nl + 1);
        __atomic_store_n(prop, off, __ATOMIC_RELEASE);
    }
    __atomic_add_fetch((uint32_t *)(a->m + 4), 1, __ATOMIC_RELEASE);        /* area serial */
    if (serial) __atomic_add_fetch((uint32_t *)(serial->m + 4), 1, __ATOMIC_RELEASE);   /* global serial */
    return 0;
}
static int pa_open(struct pa *a, const char *path) {
    int fd = open(path, O_RDWR | O_CLOEXEC); if (fd < 0) return -1;
    struct stat st; if (fstat(fd, &st) || st.st_size < PS_HDR) { close(fd); return -1; }
    a->size = (size_t)st.st_size; a->m = mmap(NULL, a->size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0); close(fd);
    return a->m == MAP_FAILED ? -1 : 0;
}
static int rdn(int fd, void *b, size_t n) { size_t g = 0; while (g < n) { ssize_t k = read(fd, (char *)b + g, n - g); if (k <= 0) { if (k < 0 && errno == EINTR) continue; return -1; } g += (size_t)k; } return 0; }

/* Starts the helper process. ROOT must be set. Returns the helper's pid or 0. */
static pid_t start_propsvc(const char *root, int *listen_fd_out) {
    char dir[PATH_MAX], sockp[PATH_MAX], areap[PATH_MAX], serp[PATH_MAX];
    snprintf(dir, sizeof dir, "%s/dev/socket", root); mkdir(dir, 0777);
    snprintf(sockp, sizeof sockp, "%s/property_service", dir);
    snprintf(areap, sizeof areap, "%s/dev/__properties__/u:object_r:default_prop:s0", root);
    snprintf(serp, sizeof serp, "%s/dev/__properties__/properties_serial", root);
    if (access(areap, W_OK) != 0 || strlen(sockp) >= sizeof(((struct sockaddr_un *)0)->sun_path)) return 0;
    int fd = socket(AF_UNIX, SOCK_STREAM, 0); if (fd < 0) return 0;
    struct sockaddr_un a; memset(&a, 0, sizeof a); a.sun_family = AF_UNIX; snprintf(a.sun_path, sizeof a.sun_path, "%s", sockp);
    unlink(sockp); if (bind(fd, (struct sockaddr *)&a, sizeof a) || listen(fd, 16)) { close(fd); return 0; }
    chmod(sockp, 0666);
    pid_t pid = fork(); if (pid < 0) { close(fd); return 0; }
    if (pid > 0) { if (listen_fd_out) *listen_fd_out = -1; close(fd); return pid; }
    prctl(PR_SET_PDEATHSIG, SIGKILL);
    struct pa A, S; int hs = pa_open(&S, serp); if (pa_open(&A, areap)) _exit(1);
    for (;;) {
        int c = accept4(fd, NULL, NULL, SOCK_CLOEXEC); if (c < 0) { if (errno == EINTR) continue; _exit(0); }
        uint32_t cmd; int32_t res = 1;
        if (!rdn(c, &cmd, 4)) {
            if (cmd == 0x00020001) {
                uint32_t l1, l2; char n[64] = { 0 }, v[128] = { 0 };
                if (!rdn(c, &l1, 4) && l1 < 64 && !rdn(c, n, l1) && !rdn(c, &l2, 4) && l2 < 128 && !rdn(c, v, l2)) {
                    if (!strncmp(n, "ctl.", 4)) res = 0;                              /* service control: accepted, handled by the app's init */
                    else res = pa_set(&A, hs == 0 ? &S : NULL, n, v);
                }
                if (BDEBUG) dprintf(2, "[propsvc] set %s=%s -> %d\n", n, v, res);
            } else if (cmd == 1) {                                                  /* legacy protocol: fixed 128-byte message, no reply */
                char m[124]; if (!rdn(c, m, sizeof m)) { m[31] = 0; m[123] = 0; pa_set(&A, hs == 0 ? &S : NULL, m, m + 32); }
                close(c); continue;
            }
        }
        (void)!write(c, &res, 4); close(c);
    }
}
