#include <ntddk.h>
#include <wdf.h>
#include <vhf.h>
#include <wdmsec.h>
#include "tablet_ioctl.h"
#define REFLECTION_DEFINE_HID_DESCRIPTOR
#include "../../shared/tablet_report.h"

typedef struct {
    VHFHANDLE vhf;
    WDFWAITLOCK lock;
    WDFFILEOBJECT owner;
    UCHAR last[REFLECTION_TABLET_REPORT_SIZE];
} TABLET_CONTEXT;
WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(TABLET_CONTEXT, TabletContext)

DRIVER_INITIALIZE DriverEntry;
EVT_WDF_DRIVER_DEVICE_ADD TabletAdd;
EVT_WDF_IO_QUEUE_IO_DEVICE_CONTROL TabletControl;
EVT_WDF_FILE_CLEANUP TabletFileCleanup;
EVT_WDF_OBJECT_CONTEXT_CLEANUP TabletCleanup;

static NTSTATUS Submit(TABLET_CONTEXT* context, UCHAR* report) {
    HID_XFER_PACKET packet;
    RtlZeroMemory(&packet, sizeof(packet));
    packet.reportBuffer = report;
    packet.reportBufferLen = REFLECTION_TABLET_REPORT_SIZE;
    packet.reportId = REFLECTION_TABLET_REPORT_ID;
    return VhfReadReportSubmit(context->vhf, &packet);
}
static void ReleasePen(TABLET_CONTEXT* context) {
    if (!context->vhf || !(context->last[1] & REFLECTION_TABLET_RANGE)) return;
    context->last[1] = REFLECTION_TABLET_RANGE | (context->last[1] & REFLECTION_TABLET_INVERT);
    context->last[6] = context->last[7] = 0;
    Submit(context, context->last);
    context->last[1] = 0;
    Submit(context, context->last);
}
void TabletFileCleanup(WDFFILEOBJECT file) {
    TABLET_CONTEXT* context = TabletContext(WdfFileObjectGetDevice(file));
    WdfWaitLockAcquire(context->lock, NULL);
    if (context->owner == file) { ReleasePen(context); context->owner = NULL; }
    WdfWaitLockRelease(context->lock);
}
void TabletCleanup(WDFOBJECT object) {
    TABLET_CONTEXT* context = TabletContext(object);
    if (context->vhf) { VhfDelete(context->vhf, TRUE); context->vhf = NULL; }
}
void TabletControl(WDFQUEUE queue, WDFREQUEST request, size_t output, size_t input, ULONG code) {
    TABLET_CONTEXT* context = TabletContext(WdfIoQueueGetDevice(queue));
    NTSTATUS status = STATUS_INVALID_DEVICE_REQUEST;
    ULONG_PTR information = 0;
    UNREFERENCED_PARAMETER(output);
    WdfWaitLockAcquire(context->lock, NULL);
    if (code == IOCTL_REFLECTION_VERSION) {
        ULONG* version;
        status = WdfRequestRetrieveOutputBuffer(request, sizeof(*version), (PVOID*)&version, NULL);
        if (NT_SUCCESS(status)) { *version = REFLECTION_TABLET_ABI; information = sizeof(*version); }
    } else if (code == IOCTL_REFLECTION_CLAIM && input == 0) {
        WDFFILEOBJECT file = WdfRequestGetFileObject(request);
        status = context->owner && context->owner != file ? STATUS_SHARING_VIOLATION : STATUS_SUCCESS;
        if (NT_SUCCESS(status)) context->owner = file;
    } else if (code == IOCTL_REFLECTION_REPORT && input == REFLECTION_TABLET_REPORT_SIZE) {
        UCHAR* report;
        if (context->owner != WdfRequestGetFileObject(request)) status = STATUS_ACCESS_DENIED;
        else {
            status = WdfRequestRetrieveInputBuffer(request, REFLECTION_TABLET_REPORT_SIZE, (PVOID*)&report, NULL);
            if (NT_SUCCESS(status)) {
                if (!reflection_tablet_report_valid(report)) status = STATUS_INVALID_PARAMETER;
                else {
                    status = Submit(context, report);
                    if (NT_SUCCESS(status)) RtlCopyMemory(context->last, report, sizeof(context->last));
                }
            }
        }
    }
    WdfWaitLockRelease(context->lock);
    WdfRequestCompleteWithInformation(request, status, information);
}
NTSTATUS TabletAdd(WDFDRIVER driver, PWDFDEVICE_INIT init) {
    WDFDEVICE device;
    WDF_OBJECT_ATTRIBUTES attributes;
    WDF_FILEOBJECT_CONFIG files;
    WDF_IO_QUEUE_CONFIG queue;
    VHF_CONFIG vhf;
    NTSTATUS status;
    DECLARE_CONST_UNICODE_STRING(name, L"\\Device\\ReflectionTablet");
    DECLARE_CONST_UNICODE_STRING(link, L"\\DosDevices\\ReflectionTablet");
    DECLARE_CONST_UNICODE_STRING(security, L"D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GRGW;;;IU)");
    UNREFERENCED_PARAMETER(driver);
    WdfDeviceInitSetDeviceType(init, FILE_DEVICE_UNKNOWN);
    WdfDeviceInitSetCharacteristics(init, FILE_DEVICE_SECURE_OPEN, FALSE);
    status = WdfDeviceInitAssignName(init, &name);
    if (!NT_SUCCESS(status)) return status;
    status = WdfDeviceInitAssignSDDLString(init, &security);
    if (!NT_SUCCESS(status)) return status;
    WDF_FILEOBJECT_CONFIG_INIT(&files, WDF_NO_EVENT_CALLBACK, WDF_NO_EVENT_CALLBACK, TabletFileCleanup);
    WdfDeviceInitSetFileObjectConfig(init, &files, WDF_NO_OBJECT_ATTRIBUTES);
    WDF_OBJECT_ATTRIBUTES_INIT_CONTEXT_TYPE(&attributes, TABLET_CONTEXT);
    attributes.EvtCleanupCallback = TabletCleanup;
    attributes.ExecutionLevel = WdfExecutionLevelPassive;
    attributes.SynchronizationScope = WdfSynchronizationScopeNone;
    status = WdfDeviceCreate(&init, &attributes, &device);
    if (!NT_SUCCESS(status)) return status;
    WDF_OBJECT_ATTRIBUTES_INIT(&attributes);
    attributes.ParentObject = device;
    status = WdfWaitLockCreate(&attributes, &TabletContext(device)->lock);
    if (!NT_SUCCESS(status)) return status;
    status = WdfDeviceCreateSymbolicLink(device, &link);
    if (!NT_SUCCESS(status)) return status;
    VHF_CONFIG_INIT(&vhf, WdfDeviceWdmGetDeviceObject(device),
        sizeof(reflection_tablet_descriptor), (PUCHAR)reflection_tablet_descriptor);
    /* Development IDs; obtain an assigned vendor ID before commercial distribution. */
    vhf.VendorID = 0x1209;
    vhf.ProductID = 0x5246;
    vhf.VersionNumber = REFLECTION_TABLET_ABI;
    status = VhfCreate(&vhf, &TabletContext(device)->vhf);
    if (!NT_SUCCESS(status)) return status;
    status = VhfStart(TabletContext(device)->vhf);
    if (!NT_SUCCESS(status)) return status;
    WDF_IO_QUEUE_CONFIG_INIT_DEFAULT_QUEUE(&queue, WdfIoQueueDispatchSequential);
    queue.EvtIoDeviceControl = TabletControl;
    return WdfIoQueueCreate(device, &queue, WDF_NO_OBJECT_ATTRIBUTES, WDF_NO_HANDLE);
}
NTSTATUS DriverEntry(PDRIVER_OBJECT object, PUNICODE_STRING registry) {
    WDF_DRIVER_CONFIG config;
    WDF_DRIVER_CONFIG_INIT(&config, TabletAdd);
    return WdfDriverCreate(object, registry, WDF_NO_OBJECT_ATTRIBUTES, &config, WDF_NO_HANDLE);
}
