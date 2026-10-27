namespace Reflection.Desktop;

public sealed record NearbyTablet(string Id, string Name, string Address, string Code, bool Trusted)
{
    public string Detail => $"{Address}  /  Code {Code}";
    public string Action => Trusted ? "Connect" : "Trust and connect";
}
