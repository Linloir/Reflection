#include <CoreFoundation/CoreFoundation.h>
#include <IOKit/IOReturn.h>
#include <dlfcn.h>
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#define REFLECTION_DEFINE_HID_DESCRIPTOR
#include "../../../shared/tablet_report.h"

/* IOHIDUserDevice is supplied by IOKit, but its C header is not in every SDK.
 * Resolve the exported ABI at runtime and fail closed if unavailable. This path
 * requires Apple's com.apple.developer.hid.virtual.device signing entitlement. */
typedef CFTypeRef (*CreateDevice)(CFAllocatorRef, CFDictionaryRef);
typedef IOReturn (*HandleReport)(CFTypeRef, const uint8_t*, CFIndex);

static void number(CFMutableDictionaryRef properties, CFStringRef key, int value) {
    CFNumberRef n = CFNumberCreate(kCFAllocatorDefault, kCFNumberIntType, &value);
    CFDictionarySetValue(properties, key, n);
    CFRelease(n);
}
static int read_report(unsigned char* report) {
    size_t received = 0;
    while (received < REFLECTION_TABLET_REPORT_SIZE) {
        ssize_t size = read(STDIN_FILENO, report + received, REFLECTION_TABLET_REPORT_SIZE - received);
        if (size < 0 && errno == EINTR) continue;
        if (size <= 0) return 0;
        received += (size_t)size;
    }
    return 1;
}
int main(int argc, char** argv) {
    if (argc != 2 || (strcmp(argv[1], "--probe") && strcmp(argv[1], "--serve"))) return 2;
    signal(SIGPIPE, SIG_IGN);
    void* library = dlopen("/System/Library/Frameworks/IOKit.framework/IOKit", RTLD_NOW | RTLD_LOCAL);
    if (!library) { fputs("IOKit is unavailable\n", stderr); return 1; }
    CreateDevice create = (CreateDevice)dlsym(library, "IOHIDUserDeviceCreate");
    HandleReport submit = (HandleReport)dlsym(library, "IOHIDUserDeviceHandleReport");
    if (!create || !submit) { fputs("Virtual HID provider is unavailable on this macOS version\n", stderr); return 1; }
    CFMutableDictionaryRef properties = CFDictionaryCreateMutable(kCFAllocatorDefault, 0,
        &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    CFDataRef descriptor = CFDataCreate(kCFAllocatorDefault, reflection_tablet_descriptor, sizeof(reflection_tablet_descriptor));
    CFDictionarySetValue(properties, CFSTR("ReportDescriptor"), descriptor);
    CFDictionarySetValue(properties, CFSTR("Product"), CFSTR("Reflection Virtual Tablet"));
    CFDictionarySetValue(properties, CFSTR("Manufacturer"), CFSTR("Reflection"));
    CFDictionarySetValue(properties, CFSTR("Transport"), CFSTR("Virtual"));
    number(properties, CFSTR("VendorID"), 0x1209);
    number(properties, CFSTR("ProductID"), 0x5246);
    number(properties, CFSTR("VersionNumber"), REFLECTION_TABLET_ABI);
    CFTypeRef device = create(kCFAllocatorDefault, properties);
    CFRelease(descriptor); CFRelease(properties);
    if (!device) {
        fputs("Virtual tablet creation denied. Install a helper signed with Apple's approved "
            "com.apple.developer.hid.virtual.device entitlement and provisioning profile.\n", stderr);
        return 1;
    }
    unsigned char last[REFLECTION_TABLET_REPORT_SIZE] = {REFLECTION_TABLET_REPORT_ID};
    int result = 0;
    if (submit(device, last, sizeof(last)) != kIOReturnSuccess ||
        write(STDOUT_FILENO, "RFD1", 4) != 4) result = 1;
    if (!result && !strcmp(argv[1], "--serve")) {
        unsigned char report[REFLECTION_TABLET_REPORT_SIZE];
        while (read_report(report)) {
            if (!reflection_tablet_report_valid(report) || submit(device, report, sizeof(report)) != kIOReturnSuccess) {
                result = 1; break;
            }
            memcpy(last, report, sizeof(last));
            if (write(STDOUT_FILENO, "\0", 1) != 1) break;
        }
    }
    if (last[1] & REFLECTION_TABLET_RANGE) {
        last[1] = REFLECTION_TABLET_RANGE | (last[1] & REFLECTION_TABLET_INVERT);
        last[6] = last[7] = 0;
        submit(device, last, sizeof(last));
        last[1] = 0;
        submit(device, last, sizeof(last));
    }
    CFRelease(device);
    dlclose(library);
    return result;
}
