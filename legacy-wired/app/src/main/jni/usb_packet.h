// SPDX-License-Identifier: GPL-3.0-only
#ifndef DIPLAY_USB_PACKET_H
#define DIPLAY_USB_PACKET_H
struct packet_result { int status; int actual; };
struct packet_result usb_packet_write(int fd, int endpoint, void *bytes, int length,
    int timeout, int (*cancelled)(void *), void *context);
#endif
