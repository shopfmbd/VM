/* Display + input bridge test (guest side): draws an animated pattern into /dev/openvm/fb and a dot where the user touches
 * (read from the input ring /dev/openvm/input). Shows that guest -> screen and touch -> guest both work. */
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
struct rec { uint32_t action; int32_t x, y; uint32_t id; };
int main(void) {
    int f = open("/dev/openvm/fb", O_RDWR); int in = open("/dev/openvm/input", O_RDONLY);
    if (f < 0) { fprintf(stderr, "fbtest: cannot open /dev/openvm/fb\n"); return 1; }
    uint32_t hdr[8]; if (pread(f, hdr, sizeof hdr, 0) != sizeof hdr || hdr[0] != 0x4256464f) { fprintf(stderr, "fbtest: bad framebuffer header\n"); return 1; }
    uint32_t W = hdr[1], H = hdr[2], stride = hdr[3];
    unsigned char *m = mmap(NULL, 4096 + (size_t)stride * H, PROT_READ | PROT_WRITE, MAP_SHARED, f, 0); if (m == MAP_FAILED) { perror("mmap fb"); return 1; }
    uint32_t *frame = (uint32_t *)(m + 20); unsigned char *px = m + 4096;
    unsigned char *ir = in >= 0 ? mmap(NULL, 8 + 256 * 16, PROT_READ, MAP_SHARED, in, 0) : MAP_FAILED; uint32_t seen = ir != MAP_FAILED ? *(uint32_t *)(ir + 4) : 0;
    int tx = -1, ty = -1, down = 0; fprintf(stderr, "fbtest: %ux%u framebuffer ready\n", W, H);
    for (uint32_t t = 0; t < 36000; t++) {
        if (ir != MAP_FAILED) { uint32_t w = *(volatile uint32_t *)(ir + 4);
            while (seen != w) { struct rec r; memcpy(&r, ir + 8 + (seen % 256) * 16, 16); seen++; tx = r.x; ty = r.y; down = r.action == 0 || r.action == 2; fprintf(stderr, "fbtest: touch action=%u x=%d y=%d\n", r.action, r.x, r.y); } }
        for (uint32_t y = 0; y < H; y++) { unsigned char *row = px + (size_t)y * stride;
            for (uint32_t x = 0; x < W; x++) { unsigned char *p = row + x * 4;
                p[0] = (unsigned char)(x * 255 / W); p[1] = (unsigned char)((y + t * 3) * 255 / H); p[2] = (unsigned char)(128 + 100 * ((x / 40 + y / 40 + t / 10) & 1)); p[3] = 255; } }
        if (tx >= 0) for (int dy = -28; dy <= 28; dy++) for (int dx = -28; dx <= 28; dx++) { int X = tx + dx, Y = ty + dy; if (X < 0 || Y < 0 || X >= (int)W || Y >= (int)H || dx * dx + dy * dy > 784) continue;
            unsigned char *p = px + (size_t)Y * stride + X * 4; p[0] = down ? 255 : 255; p[1] = down ? 255 : 80; p[2] = down ? 255 : 80; p[3] = 255; }
        __atomic_add_fetch(frame, 1, __ATOMIC_RELEASE); usleep(33000);
    }
    return 0;
}
