using System.Text.Json;

namespace Reflection.Desktop;

internal sealed record DriverState(bool Available, bool Installed, bool PackageAvailable,
    string DeviceDetail, string PackageDetail)
{
    public bool CanSelectDriver => Available || PackageAvailable;
    public bool CanInstall => !Available && PackageAvailable;
    public bool CanUninstall => Installed;
    public string DeviceText => Available ? "Driver ready." :
        Installed ? "Driver installed, unavailable. " + DeviceDetail : "Driver not installed.";

    public static DriverState FromJson(JsonElement driver) => new(
        driver.GetProperty("available").GetBoolean(),
        driver.GetProperty("installed").GetBoolean(),
        driver.GetProperty("packageAvailable").GetBoolean(),
        driver.GetProperty("detail").GetString() ?? "",
        driver.GetProperty("package").GetProperty("detail").GetString() ?? "");
}
