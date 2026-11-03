/* Init-service self-test: ANDROID_SOCKET_* inheritance, /dev/socket path mapping and the property service. */
#define _GNU_SOURCE
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
static int conn(const char *path) {
    int s = socket(AF_UNIX, SOCK_STREAM, 0); struct sockaddr_un a; memset(&a, 0, sizeof a); a.sun_family = AF_UNIX; strcpy(a.sun_path, path);
    for (int i = 0; i < 50; i++) { if (!connect(s, (struct sockaddr *)&a, sizeof a)) return s; usleep(100000); }
    return -1;
}
static int setprop(const char *k, const char *v) {
    int s = conn("/dev/socket/property_service"); if (s < 0) { perror("connect property_service"); return -1; }
    unsigned char b[256]; size_t n = 0; uint32_t cmd = 0x00020001, l;
    memcpy(b, &cmd, 4); n = 4; l = strlen(k); memcpy(b + n, &l, 4); n += 4; memcpy(b + n, k, l); n += l; l = strlen(v); memcpy(b + n, &l, 4); n += 4; memcpy(b + n, v, l); n += l;
    if (write(s, b, n) != (ssize_t)n) return -1; int32_t r = -1; if (read(s, &r, 4) != 4) return -1; close(s); return r;
}
int main(int argc, char **argv) {
    if (argc > 1 && !strcmp(argv[1], "server")) {
        const char *e = getenv("ANDROID_SOCKET_zygote"); if (!e) { fprintf(stderr, "[server] no ANDROID_SOCKET_zygote\n"); return 1; }
        int l = atoi(e), c = accept4(l, NULL, NULL, SOCK_CLOEXEC); char b[32] = {0}; if (read(c, b, 4) != 4) return 2; fprintf(stderr, "[server] got %s\n", b); (void)!write(c, "pong", 4); return 0;
    }
    int s = conn("/dev/socket/zygote"); if (s < 0) { fprintf(stderr, "FAIL: cannot connect /dev/socket/zygote\n"); return 1; }
    (void)!write(s, "ping", 4); char b[8] = {0}; if (read(s, b, 4) != 4 || strcmp(b, "pong")) { fprintf(stderr, "FAIL: bad reply\n"); return 1; }
    fprintf(stderr, "[client] zygote socket ok\n");
    int r1 = setprop("sys.openvm.test", "works"); int r2 = setprop("ro.build.version.release", "99"); int r3 = setprop("sys.openvm.test", "again"); int r4 = setprop("debug.a.b.c", "deep");
    fprintf(stderr, "[client] setprop results: new=%d ro=%d update=%d nested=%d\n", r1, r2, r3, r4);
    if (r1 != 0 || r2 != 0xB || r3 != 0 || r4 != 0) { fprintf(stderr, "FAIL\n"); return 1; }
    fprintf(stderr, "PASS\n"); return 0;
}
