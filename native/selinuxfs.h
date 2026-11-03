/*
 * selinuxfs.h - a fake, permissive selinuxfs for the guest (included from vmloader.c).
 *
 * Android's native services link libselinux and insist on a working SELinux: servicemanager does
 * CHECK(selinux_status_open(true) >= 0), hwservicemanager asks for the enforcing mode and the class "hwservice_manager",
 * and every addService() goes through selinux_check_access(). An app may not read the phone's real /sys/fs/selinux
 * (EACCES), and it must not be able to change it, so the guest gets its own tree under <rootfs>/sys/fs/selinux:
 *
 *   status, enforce(0 = permissive), policyvers, ...   plain files written once by setup_selinuxfs()
 *   class/<name>/index, class/<name>/perms/<perm>      the class / permission numbers libselinux looks up
 *   access, context, create, member, relabel, user     "request" files: libselinux write()s a question and read()s the answer.
 *                                                      Every open() of one of these gets a private scratch file with a canned
 *                                                      "everything is allowed" answer (see sel_open_hook), deleted when open returns.
 *
 * statfs("/sys/fs/selinux") is answered with f_type = SELINUX_MAGIC (libselinux checks it before it trusts the mount point).
 * Writes to /proc/<pid>/attr/{current,exec,fscreate,...} (setcon/setexeccon, used by zygote for every app it forks)
 * are redirected to /dev/null, because the host kernel rejects them for an app.
 */
#include <dirent.h>

#define SEL_MAGIC 0xf97cff8cULL
#define SEL_MARK  ".openvm-selinuxfs-v1"

static char SDIR[PATH_MAX];      /* <rootfs parent>/.selinux : scratch files for request-file opens */
static int SSEQ;

/* class name -> space separated permission names (the permission number is its 1-based position) */
static const struct { const char *cls, *perms; } SEL_CLASSES[] = {
    { "service_manager",   "add find list" },
    { "hwservice_manager", "add find list" },
    { "binder",            "impersonate call set_context_mgr transfer" },
    { "property_service",  "set" },
    { "zygote",            "specialize" },
    { "security",          "compute_av compute_create compute_member check_context load_policy compute_relabel compute_user setenforce setbool setsecparam setcheckreqprot read_policy validate_trans" },
    { "system",            "ipc_info syslog_read syslog_mod syslog_console module_request module_load" },
    { "process",           "fork transition sigchld sigkill sigstop signull signal ptrace getsched setsched getsession getpgid setpgid getcap setcap share getattr setexec setfscreate noatsecure siginh setrlimit rlimitinh dyntransition setcurrent execmem execstack execheap setkeycreate setsockcreate getrlimit" },
    { "file",              "ioctl read write create getattr setattr lock relabelfrom relabelto append map unlink link rename execute quotaon mounton audit_access open execmod watch watch_mount watch_sb watch_with_perm watch_reads" },
    { "dir",               "ioctl read write create getattr setattr lock relabelfrom relabelto append map unlink link rename execute quotaon mounton audit_access open execmod watch watch_mount watch_sb watch_with_perm watch_reads add_name remove_name reparent search rmdir" },
    { NULL, NULL }
};

static void sel_mkdirs(const char *path) {
    char b[PATH_MAX]; snprintf(b, sizeof b, "%s", path);
    for (char *p = b + 1; *p; p++) if (*p == '/') { *p = 0; mkdir(b, 0755); *p = '/'; }
    mkdir(b, 0755);
}
static int sel_put(const char *path, const void *data, size_t n) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644); if (fd < 0) return -1;
    ssize_t k = n ? write(fd, data, n) : 0; close(fd);
    return k == (ssize_t)n ? 0 : -1;
}

/* Creates <rootfs>/sys/fs/selinux (once) and empties the scratch directory. Needs ROOT. */
static void setup_selinuxfs(void) {
    char d[PATH_MAX], f[PATH_MAX];
    const char *sl = strrchr(ROOT, '/');
    if (sl) { int n = snprintf(SDIR, sizeof SDIR, "%.*s/.selinux", (int)(sl - ROOT), ROOT); if (n <= 0 || (size_t)n >= sizeof SDIR || (mkdir(SDIR, 0700) && errno != EEXIST)) SDIR[0] = 0; }
    if (SDIR[0]) { DIR *dd = opendir(SDIR); if (dd) { struct dirent *e; while ((e = readdir(dd))) { if (e->d_name[0] == '.') continue; snprintf(f, sizeof f, "%s/%s", SDIR, e->d_name); unlink(f); } closedir(dd); } }
    snprintf(d, sizeof d, "%s/sys/fs/selinux", ROOT); snprintf(f, sizeof f, "%s/" SEL_MARK, d);
    if (access(f, F_OK) == 0) return;
    sel_mkdirs(d);
    unsigned char st[4096]; memset(st, 0, sizeof st);
    uint32_t v[5] = { 1 /*version*/, 2 /*sequence (even = stable)*/, 0 /*enforcing: permissive*/, 1 /*policyload*/, 0 /*deny_unknown*/ }; memcpy(st, v, sizeof v);
    static const struct { const char *name, *val; } txt[] = {
        { "enforce", "0" }, { "policyvers", "33" }, { "checkreqprot", "0" }, { "deny_unknown", "0" }, { "mls", "1" }, { "reject_unknown", "0" },
        { "access", "" }, { "context", "" }, { "create", "" }, { "member", "" }, { "relabel", "" }, { "user", "" }, { NULL, NULL } };
    snprintf(f, sizeof f, "%s/status", d); sel_put(f, st, sizeof st);
    for (int i = 0; txt[i].name; i++) { snprintf(f, sizeof f, "%s/%s", d, txt[i].name); sel_put(f, txt[i].val, strlen(txt[i].val)); }
    for (int c = 0; SEL_CLASSES[c].cls; c++) {
        char cd[PATH_MAX], val[16]; snprintf(cd, sizeof cd, "%s/class/%s/perms", d, SEL_CLASSES[c].cls); sel_mkdirs(cd);
        snprintf(f, sizeof f, "%s/class/%s/index", d, SEL_CLASSES[c].cls); snprintf(val, sizeof val, "%d", c + 1); sel_put(f, val, strlen(val));
        char pl[1024]; snprintf(pl, sizeof pl, "%s", SEL_CLASSES[c].perms); int k = 1;
        for (char *sp = NULL, *tok = strtok_r(pl, " ", &sp); tok; tok = strtok_r(NULL, " ", &sp), k++) {
            snprintf(f, sizeof f, "%s/%s", cd, tok); snprintf(val, sizeof val, "%d", k); sel_put(f, val, strlen(val));
        }
    }
    snprintf(f, sizeof f, "%s/" SEL_MARK, d); sel_put(f, "1", 1);
}

/* /proc/self/attr/X, /proc/thread-self/attr/X, /proc/self/task/N/attr/X, /proc/N/attr/X ... : the process-attribute files */
static int sel_is_attr(const char *g) {
    if (strncmp(g, "/proc/", 6)) return 0;
    const char *a = strstr(g, "/attr/"); if (!a) return 0;
    const char *nm = a + 6;
    if (strcmp(nm, "current") && strcmp(nm, "exec") && strcmp(nm, "fscreate") && strcmp(nm, "keycreate") && strcmp(nm, "sockcreate")) return 0;
    for (const char *p = g + 6; p < a; ) {                       /* every component before /attr/ must be self, thread-self, task or a number */
        const char *e = p; while (e < a && *e != '/') e++;
        size_t l = (size_t)(e - p); int ok = (l == 4 && !strncmp(p, "self", 4)) || (l == 11 && !strncmp(p, "thread-self", 11)) || (l == 4 && !strncmp(p, "task", 4));
        if (!ok) { ok = l > 0; for (const char *q = p; q < e; q++) if (*q < '0' || *q > '9') ok = 0; }
        if (!ok) return 0;
        p = e < a ? e + 1 : e;
    }
    return 1;
}

static int sel_set_path(pid_t pid, regs_t *r, struct tp *t, int idx, const char *s) {
    unsigned long dst = (R_SP(r) - 256 - 8192) & ~15UL;
    if (wr(pid, dst, s, strlen(s) + 1)) return 0;
    *ARG(r, idx) = dst; t->touched |= 1UL << idx; set_regs(pid, r); return 1;
}

/* open()/openat() entry. Returns 1 when the path argument was replaced (the open then runs for real on the replacement). */
static int sel_open_hook(pid_t pid, struct tp *t, regs_t *r) {
    int pi = t->nr == SYS_openat ? 1 : 0; char g[PATH_MAX];
    unsigned long p = *ARG(r, pi); if (!p || rd_str(pid, p, g, sizeof g) || g[0] != '/') return 0;
    unsigned long flags = *ARG(r, pi + 1);
    if (sel_is_attr(g)) {
        if ((flags & 3) == O_RDONLY) return 0;                  /* reading the (host) context of a process is fine */
        snprintf(t->path, sizeof t->path, "%s", g);
        return sel_set_path(pid, r, t, pi, "/dev/null");        /* setcon()/setexeccon(): pretend it worked */
    }
    if (SDIR[0] && has_prefix(g, "/sys/fs/selinux") && g[15] == '/') {
        const char *n = g + 16; static const char *req[] = { "access", "context", "create", "member", "relabel", "user", NULL };
        int hit = 0; for (int i = 0; req[i]; i++) if (!strcmp(n, req[i])) hit = 1;
        if (!hit) return 0;
        char fp[PATH_MAX]; snprintf(fp, sizeof fp, "%s/%d", SDIR, ++SSEQ);
        if (strlen(fp) >= sizeof t->selpath) return 0;
        static char ans[2048]; static int ans_init;
        if (!ans_init) { memset(ans, ' ', 1024); snprintf(ans + 1024, sizeof ans - 1024, "ffffffff ffffffff 0 0 1 0\n"); ans_init = 1; }
        /* The question is write()n over the start of the file and the answer read() from where the write ended, so the answer sits behind
         * a block of blanks (sscanf skips them) and every open gets a pristine copy. */
        if (sel_put(fp, !strcmp(n, "access") ? ans : "", !strcmp(n, "access") ? 1024 + strlen(ans + 1024) : 0)) return 0;
        chmod(fp, 0666);
        snprintf(t->path, sizeof t->path, "%s", g);
        if (!sel_set_path(pid, r, t, pi, fp)) { unlink(fp); return 0; }
        snprintf(t->selpath, sizeof t->selpath, "%s", fp);
        return 1;
    }
    return 0;
}
