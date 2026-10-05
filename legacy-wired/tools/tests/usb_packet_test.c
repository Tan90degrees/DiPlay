// SPDX-License-Identifier: GPL-3.0-only
/* Execute the production usbfs URB loop against a deterministic fake kernel. */
#include <assert.h>
#include <errno.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <time.h>
#include <stdio.h>

static struct usbdevfs_urb *pending;
static int submissions, discards, polls, finished_at, actual, status, cancel_at;
static int submit_error, reap_error, poll_interrupt, reap_interrupt, cancel_race;
static long long elapsed;
static int fake_clock(clockid_t clock, struct timespec *now) {
    assert(clock == CLOCK_MONOTONIC);
    now->tv_sec = elapsed / 1000; now->tv_nsec = (elapsed % 1000) * 1000000;
    return 0;
}
static int fake_poll(struct pollfd *wait, nfds_t count, int timeout) {
    assert(count == 1 && wait->fd == 42 && wait->events == POLLOUT);
    assert(timeout > 0 && timeout <= 250);
    polls++;
    if (poll_interrupt) { poll_interrupt = 0; errno = EINTR; return -1; }
    elapsed += timeout;
    return elapsed >= finished_at;
}
static int fake_ioctl(int fd, unsigned long operation, void *arg) {
    assert(fd == 42);
    if (operation == USBDEVFS_SUBMITURB) {
        submissions++;
        if (submit_error) { errno = submit_error; return -1; }
        pending = arg;
        assert(pending->type == USBDEVFS_URB_TYPE_BULK && pending->endpoint == 4);
        assert(pending->buffer_length == 100 && ((unsigned char *)pending->buffer)[0] == 0x4e);
        return 0;
    }
    if (operation == USBDEVFS_DISCARDURB) {
        discards++; assert(arg == pending);
        if (!cancel_race) status = -ECONNRESET;
        return 0;
    }
    if (operation == USBDEVFS_REAPURB || operation == USBDEVFS_REAPURBNDELAY) {
        if (reap_interrupt) { reap_interrupt = 0; errno = EINTR; return -1; }
        if (reap_error) { errno = reap_error; return -1; }
        if (operation == USBDEVFS_REAPURBNDELAY && elapsed < finished_at) { errno = EAGAIN; return -1; }
        pending->status = status; pending->actual_length = actual;
        *(struct usbdevfs_urb **)arg = pending;
        return 0;
    }
    assert(0); return -1;
}
#define clock_gettime fake_clock
#define poll fake_poll
#define ioctl fake_ioctl
#include "../../app/src/main/jni/usb_packet.c"
#undef clock_gettime
#undef poll
#undef ioctl

static int cancelled(void *context) { assert(context == NULL); return elapsed >= cancel_at; }
static void reset(void) {
    submissions = discards = polls = elapsed = 0; pending = NULL;
    finished_at = 750; actual = 100; status = 0; cancel_at = 30000;
    submit_error = reap_error = poll_interrupt = reap_interrupt = cancel_race = 0;
}
static struct packet_result run(int timeout) {
    unsigned char bytes[100] = { 0x4e };
    struct packet_result result = usb_packet_write(42, 4, bytes, 100, timeout, cancelled, NULL);
    assert(submissions <= 1); // Never replay after timeout, interruption or partial completion.
    return result;
}
int main(void) {
    struct packet_result result;
    reset(); result = run(20000); assert(result.status == 0 && result.actual == 100 && polls == 3 && discards == 0);
    reset(); finished_at = 15000; result = run(20000); assert(result.status == 0 && elapsed == 15000);
    reset(); finished_at = 30000; actual = 0; result = run(20000); assert(result.status == -ETIMEDOUT && result.actual == 0 && elapsed == 20000 && discards == 1);
    reset(); finished_at = 30000; actual = 32; result = run(2000); assert(result.status == -ETIMEDOUT && result.actual == 32 && elapsed == 2000);
    reset(); finished_at = 30000; cancel_at = 250; actual = 32; result = run(20000); assert(result.status == -ECANCELED && result.actual == 32 && elapsed == 250);
    reset(); cancel_at = 0; result = run(20000); assert(result.status == -ECANCELED && submissions == 0);
    reset(); finished_at = 30000; cancel_at = 250; cancel_race = 1; result = run(20000); assert(result.status == 0 && result.actual == 100 && discards == 1);
    reset(); submit_error = EACCES; result = run(20000); assert(result.status == -EACCES && discards == 0);
    reset(); reap_error = ENODEV; result = run(20000); assert(result.status == -ENODEV && discards == 1);
    reset(); poll_interrupt = reap_interrupt = 1; result = run(20000); assert(result.status == 0 && result.actual == 100 && discards == 0);
    reset(); actual = 32; result = run(2000); assert(result.status == 0 && result.actual == 32);
    reset(); status = -EPIPE; actual = 32; result = run(2000); assert(result.status == -EPIPE && result.actual == 32);
    puts("usb_packet: 12 native scenarios passed");
    return 0;
}
