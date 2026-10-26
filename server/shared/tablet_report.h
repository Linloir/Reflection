#ifndef REFLECTION_TABLET_REPORT_H
#define REFLECTION_TABLET_REPORT_H

/* Wire ABI shared by the host and virtual HID providers. Little endian. */
#define REFLECTION_TABLET_ABI 1
#define REFLECTION_TABLET_REPORT_SIZE 10
#define REFLECTION_TABLET_REPORT_ID 1
#define REFLECTION_TABLET_TIP 0x01
#define REFLECTION_TABLET_BARREL 0x02
#define REFLECTION_TABLET_INVERT 0x04
#define REFLECTION_TABLET_ERASER 0x08
#define REFLECTION_TABLET_RANGE 0x10

#ifdef REFLECTION_DEFINE_HID_DESCRIPTOR
static const unsigned char reflection_tablet_descriptor[] = {
    0x05,0x0d, 0x09,0x02, 0xa1,0x01, 0x85,0x01, /* Digitizers / Pen */
    0x09,0x20, 0xa1,0x00,                       /* Stylus */
    0x09,0x42, 0x09,0x44, 0x09,0x3c, 0x09,0x45, 0x09,0x32,
    0x15,0x00, 0x25,0x01, 0x75,0x01, 0x95,0x05, 0x81,0x02,
    0x75,0x03, 0x95,0x01, 0x81,0x03,           /* Padding */
    0x05,0x01, 0x09,0x30, 0x09,0x31,           /* X, Y */
    0x15,0x00, 0x26,0xff,0x7f, 0x35,0x00, 0x46,0xff,0x7f,
    0x55,0x0d, 0x65,0x11,                      /* Centimeters, exponent -3 */
    0x75,0x10, 0x95,0x02, 0x81,0x02,
    0x05,0x0d, 0x09,0x30,                      /* Tip pressure */
    0x27,0xff,0xff,0x00,0x00, 0x45,0x00, 0x55,0x00, 0x65,0x00, 0x95,0x01, 0x81,0x02,
    0x09,0x3d, 0x09,0x3e,                      /* X tilt, Y tilt */
    0x15,0xa6, 0x25,0x5a, 0x35,0xa6, 0x45,0x5a, 0x65,0x14,
    0x75,0x08, 0x95,0x02, 0x81,0x02,
    0xc0,0xc0
};
#endif

static inline int reflection_tablet_report_valid(const unsigned char* report) {
    unsigned flags = report[1];
    return report[0] == REFLECTION_TABLET_REPORT_ID && !(flags & ~0x1f) &&
        report[3] < 128 && report[5] < 128 &&
        (signed char)report[8] >= -90 && (signed char)report[8] <= 90 &&
        (signed char)report[9] >= -90 && (signed char)report[9] <= 90 &&
        (!(flags & (REFLECTION_TABLET_TIP | REFLECTION_TABLET_ERASER)) ||
            (flags & REFLECTION_TABLET_RANGE)) &&
        (!(flags & REFLECTION_TABLET_ERASER) || (flags & REFLECTION_TABLET_INVERT)) &&
        !((flags & REFLECTION_TABLET_TIP) && (flags & REFLECTION_TABLET_ERASER)) &&
        ((flags & (REFLECTION_TABLET_TIP | REFLECTION_TABLET_ERASER)) ||
            (!report[6] && !report[7]));
}
#endif
