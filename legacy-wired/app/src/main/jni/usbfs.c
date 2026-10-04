// SPDX-License-Identifier: GPL-3.0-only
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/usbdevice_fs.h>
#include <linux/if.h>
#include <linux/if_tun.h>
#include <poll.h>
#include <string.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define JNI_METHOD(name) Java_com_shihab_diplay_legacy_NativeUsbIo_##name
#define UNUSED() (void)env; (void)self

JNIEXPORT jint JNICALL JNI_METHOD(duplicate)(JNIEnv *env, jobject self, jint fd) {
    UNUSED();
    int result = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    return result < 0 ? -errno : result;
}
JNIEXPORT jint JNICALL JNI_METHOD(control)(JNIEnv *env, jobject self, jint fd,
        jint type, jint request, jint value, jint index, jbyteArray data, jint timeout) {
    (void)self;
    jsize length = (*env)->GetArrayLength(env, data);
    if (length > 16384 || timeout <= 0 || timeout > 5000) return -EINVAL;
    unsigned char bytes[16384];
    memset(bytes, 0, sizeof(bytes));
    if (!(type & 0x80)) (*env)->GetByteArrayRegion(env, data, 0, length, (jbyte *)bytes);
    struct usbdevfs_ctrltransfer transfer = {
        .bRequestType = type, .bRequest = request, .wValue = value, .wIndex = index,
        .wLength = length, .timeout = timeout, .data = bytes,
    };
    int result = ioctl(fd, USBDEVFS_CONTROL, &transfer);
    int saved_errno = errno;
    if (result > 0 && (type & 0x80)) (*env)->SetByteArrayRegion(env, data, 0, result, (jbyte *)bytes);
    return result < 0 ? -saved_errno : result;
}
JNIEXPORT jint JNICALL JNI_METHOD(configuration)(JNIEnv *env, jobject self, jint fd, jint value) {
    UNUSED();
    // Do not reset an already active configuration (another pipe may own it).
    unsigned char active = 0;
    struct usbdevfs_ctrltransfer get = { .bRequestType = 0x80, .bRequest = 8,
        .wLength = 1, .timeout = 1000, .data = &active };
    if (ioctl(fd, USBDEVFS_CONTROL, &get) == 1 && active == value) return 0;
    unsigned int configuration = value;
    return ioctl(fd, USBDEVFS_SETCONFIGURATION, &configuration) < 0 ? -errno : 0;
}
JNIEXPORT jint JNICALL JNI_METHOD(claim)(JNIEnv *env, jobject self, jint fd, jint number) {
    UNUSED();
    unsigned int interface_number = number;
    if (ioctl(fd, USBDEVFS_CLAIMINTERFACE, &interface_number) == 0) return 0;
    if (errno != EBUSY) return -errno;
    struct usbdevfs_getdriver owner = { .interface = number };
    if (ioctl(fd, USBDEVFS_GETDRIVER, &owner) < 0) return -errno;
    if (strcmp(owner.driver, "usbfs") == 0) return -EBUSY;
    // Android's force-claim equivalent. No root is needed if UsbManager authorized the fd.
    struct usbdevfs_ioctl disconnect = { .ifno = number, .ioctl_code = USBDEVFS_DISCONNECT, .data = NULL };
    if (ioctl(fd, USBDEVFS_IOCTL, &disconnect) < 0) return -errno;
    if (ioctl(fd, USBDEVFS_CLAIMINTERFACE, &interface_number) == 0) return 1;
    int saved_errno = errno;
    disconnect.ioctl_code = USBDEVFS_CONNECT;
    ioctl(fd, USBDEVFS_IOCTL, &disconnect);
    return -saved_errno;
}
JNIEXPORT jint JNICALL JNI_METHOD(alternate)(JNIEnv *env, jobject self, jint fd, jint number, jint alternate) {
    UNUSED();
    struct usbdevfs_setinterface setting = { .interface = number, .altsetting = alternate };
    return ioctl(fd, USBDEVFS_SETINTERFACE, &setting) < 0 ? -errno : 0;
}
JNIEXPORT jint JNICALL JNI_METHOD(bulk)(JNIEnv *env, jobject self, jint fd, jint endpoint,
        jbyteArray data, jint offset, jint length, jint timeout) {
    (void)self;
    jsize capacity = (*env)->GetArrayLength(env, data);
    if (offset < 0 || length < 1 || length > 16384 || offset > capacity - length || timeout <= 0 || timeout > 250) return -EINVAL;
    unsigned char bytes[16384];
    if (!(endpoint & 0x80)) (*env)->GetByteArrayRegion(env, data, offset, length, (jbyte *)bytes);
    struct usbdevfs_bulktransfer transfer = { .ep = endpoint, .len = length, .timeout = timeout, .data = bytes };
    int result = ioctl(fd, USBDEVFS_BULK, &transfer);
    int saved_errno = errno;
    if (result > 0 && (endpoint & 0x80)) (*env)->SetByteArrayRegion(env, data, offset, result, (jbyte *)bytes);
    return result < 0 ? -saved_errno : result;
}
JNIEXPORT jint JNICALL JNI_METHOD(release)(JNIEnv *env, jobject self, jint fd, jint number) {
    UNUSED();
    unsigned int interface_number = number;
    return ioctl(fd, USBDEVFS_RELEASEINTERFACE, &interface_number) < 0 ? -errno : 0;
}
JNIEXPORT jint JNICALL JNI_METHOD(driver)(JNIEnv *env, jobject self, jint fd, jint number, jbyteArray name) {
    (void)self;
    struct usbdevfs_getdriver owner;
    memset(&owner, 0, sizeof(owner));
    owner.interface = number;
    if (ioctl(fd, USBDEVFS_GETDRIVER, &owner) < 0) return errno == ENODATA ? 0 : -errno;
    owner.driver[sizeof(owner.driver) - 1] = '\0';
    size_t length = strlen(owner.driver);
    if (length > (size_t)(*env)->GetArrayLength(env, name)) return -EINVAL;
    (*env)->SetByteArrayRegion(env, name, 0, length, (jbyte *)owner.driver);
    return length;
}
JNIEXPORT jint JNICALL JNI_METHOD(disconnect)(JNIEnv *env, jobject self, jint fd, jint number) {
    UNUSED();
    struct usbdevfs_ioctl disconnect = { .ifno = number, .ioctl_code = USBDEVFS_DISCONNECT, .data = NULL };
    return ioctl(fd, USBDEVFS_IOCTL, &disconnect) < 0 ? -errno : 0;
}
JNIEXPORT jint JNICALL JNI_METHOD(reconnect)(JNIEnv *env, jobject self, jint fd, jint number) {
    UNUSED();
    struct usbdevfs_ioctl connect = { .ifno = number, .ioctl_code = USBDEVFS_CONNECT, .data = NULL };
    return ioctl(fd, USBDEVFS_IOCTL, &connect) < 0 ? -errno : 0;
}
JNIEXPORT void JNICALL JNI_METHOD(close)(JNIEnv *env, jobject self, jint fd) { UNUSED(); close(fd); }
JNIEXPORT jint JNICALL JNI_METHOD(tunRead)(JNIEnv *env, jobject self, jint fd, jbyteArray data, jint timeout) {
    (void)self;
    if (timeout <= 0 || timeout > 250) return -EINVAL;
    struct pollfd poll_fd = { .fd = fd, .events = POLLIN };
    int ready = poll(&poll_fd, 1, timeout);
    if (ready <= 0) return ready == 0 ? -EAGAIN : -errno;
    unsigned char bytes[16384];
    jsize length = (*env)->GetArrayLength(env, data);
    if (length > 16384) return -EINVAL;
    int result = read(fd, bytes, length);
    int saved_errno = errno;
    if (result > 0) (*env)->SetByteArrayRegion(env, data, 0, result, (jbyte *)bytes);
    return result < 0 ? -saved_errno : result;
}
JNIEXPORT jstring JNICALL JNI_METHOD(tunName)(JNIEnv *env, jobject self, jint fd) {
    (void)self;
    struct ifreq request;
    memset(&request, 0, sizeof(request));
    if (ioctl(fd, TUNGETIFF, &request) < 0) return NULL;
    request.ifr_name[IFNAMSIZ - 1] = '\0';
    return (*env)->NewStringUTF(env, request.ifr_name);
}
JNIEXPORT jint JNICALL JNI_METHOD(tunWrite)(JNIEnv *env, jobject self, jint fd, jbyteArray data) {
    (void)self;
    struct pollfd poll_fd = { .fd = fd, .events = POLLOUT };
    int ready = poll(&poll_fd, 1, 250);
    if (ready <= 0) return ready == 0 ? -ETIMEDOUT : -errno;
    jsize length = (*env)->GetArrayLength(env, data);
    if (length < 1 || length > 65535) return -EINVAL;
    unsigned char *bytes = malloc((size_t)length);
    if (bytes == NULL) return -ENOMEM;
    (*env)->GetByteArrayRegion(env, data, 0, length, (jbyte *)bytes);
    if ((*env)->ExceptionCheck(env)) { free(bytes); return -EINVAL; }
    int result = write(fd, bytes, length);
    int saved_errno = errno;
    free(bytes);
    return result < 0 ? -saved_errno : result;
}
