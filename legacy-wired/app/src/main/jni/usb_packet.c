// SPDX-License-Identifier: GPL-3.0-only
#include "usb_packet.h"
#include <errno.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>

static long long monotonic_ms(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (long long)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

/* Exactly one submitted URB. Polling never resubmits the NTB. The caller owns the
 * fd lease until cancellation has been reaped, and is its only async IO user.
 * Unlike USBDEVFS_BULK, REAPURB retains actual_length even when status is negative.
 */
struct packet_result usb_packet_write(int fd, int endpoint, void *bytes, int length,
        int timeout, int (*cancelled)(void *), void *context) {
    struct packet_result result = { -EINVAL, 0 };
    if (length < 1 || length > 16384 || endpoint & 0x80 ||
            timeout < 1 || timeout > 20000) return result;
    if (cancelled(context)) { result.status = -ECANCELED; return result; }
    struct usbdevfs_urb urb;
    memset(&urb, 0, sizeof(urb));
    urb.type = USBDEVFS_URB_TYPE_BULK;
    urb.endpoint = endpoint;
    urb.buffer = bytes;
    urb.buffer_length = length;
    if (ioctl(fd, USBDEVFS_SUBMITURB, &urb) < 0) {
        result.status = -errno;
        return result;
    }
    long long deadline = monotonic_ms() + timeout;
    int reason = 0;
    for (;;) {
        struct usbdevfs_urb *completed = NULL;
        if (ioctl(fd, USBDEVFS_REAPURBNDELAY, &completed) == 0) {
            result.status = completed == &urb ? urb.status : -EIO;
            result.actual = completed == &urb ? urb.actual_length : 0;
            return result;
        }
        if (errno != EAGAIN && errno != EINTR) { reason = -errno; break; }
        if (cancelled(context)) { reason = -ECANCELED; break; }
        long long remaining = deadline - monotonic_ms();
        if (remaining <= 0) { reason = -ETIMEDOUT; break; }
        struct pollfd wait = { .fd = fd, .events = POLLOUT };
        if (poll(&wait, 1, remaining < 250 ? (int)remaining : 250) < 0 && errno != EINTR) {
            reason = -errno;
            break;
        }
    }
    /* EINVAL from DISCARDURB also means completion won the race. Reap that
     * completion before releasing storage. Device removal destroys pending IO.
     */
    ioctl(fd, USBDEVFS_DISCARDURB, &urb);
    struct usbdevfs_urb *completed = NULL;
    int reaped;
    do { reaped = ioctl(fd, USBDEVFS_REAPURB, &completed); } while (reaped < 0 && errno == EINTR);
    result.status = reaped < 0 ? -errno : completed == &urb ? urb.status : -EIO;
    result.actual = reaped == 0 && completed == &urb ? urb.actual_length : -1;
    if (result.status == -ENOENT || result.status == -ECONNRESET) result.status = reason;
    return result;
}
