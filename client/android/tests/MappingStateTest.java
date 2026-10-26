import io.github.linloir.reflection.core.MappingState;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class MappingStateTest {
    private static final String DISPLAY = "{\"id\":\"left\",\"name\":\"Left display\",\"x\":-1920,\"y\":0,\"width\":1920,\"height\":1080,\"primary\":false}";
    private static final String STATUS = "{\"revision\":1,\"mode\":\"display\",\"selected\":\"left\",\"target\":{\"x\":-1920,\"y\":0,\"width\":1920,\"height\":1080},\"displays\":[" + DISPLAY + "],\"pending\":false}";
    private static MappingState decode(String json) throws IOException { return MappingState.decode(json.getBytes(StandardCharsets.UTF_8)); }
    private static void rejected(String json) throws Exception {
        try { decode(json); throw new AssertionError("Accepted invalid mapping JSON: " + json); }
        catch (IOException expected) {}
    }
    public static void main(String[] args) throws Exception {
        MappingState state = decode(STATUS);
        if (state.revision != 1 || !state.selected.equals("left") || state.target.x != -1920 || state.displays.size() != 1)
            throw new AssertionError("Display mapping was not decoded");
        if (!state.label().equals("Left display")) throw new AssertionError("Display label");
        decode(STATUS.replace("\"pending\":false", "\"pending\":false,\"future\":{\"nested\":[1,true,null,\"ok\"]}"));
        rejected(STATUS + "garbage");
        rejected(STATUS.replace("\"revision\":1", "\"revision\":0"));
        rejected(STATUS.replace("\"revision\":1", "\"revision\":1.5"));
        rejected(STATUS.replace("\"revision\":1", "\"revision\":1,\"revision\":2"));
        rejected(STATUS.replace("\"width\":1920", "\"width\":0"));
        rejected(STATUS.replace("\"x\":-1920", "\"x\":1e999"));
        rejected(STATUS.replace("\"primary\":false", "\"primary\":\"false\""));
        rejected(STATUS.replace("\"selected\":\"left\"", "\"selected\":\"missing\""));
        rejected(STATUS.replace("[" + DISPLAY + "]", "[]"));
        rejected(STATUS.replace("[" + DISPLAY + "]", "[" + DISPLAY + "," + DISPLAY + "]"));
        rejected(STATUS.replace("\"id\":\"left\"", "\"id\":\"bad\\u0000id\""));
        rejected(STATUS.replace("\"mode\":\"display\"", "\"mode\":\"unknown\""));
        rejected(STATUS.replace("Left display", "bad\\ud800"));
        rejected(STATUS.replace("Left display", "bad\\u+123"));
        decode(STATUS.replace("Left display", "Display \\ud83d\\udda5"));
        try { MappingState.decode(new byte[] {(byte) 0xff}); throw new AssertionError("Invalid UTF-8 accepted"); }
        catch (IOException expected) {}
        try { MappingState.decode(new byte[4096]); throw new AssertionError("Oversized status accepted"); }
        catch (IOException expected) {}
        byte[] request = MappingState.request(12, MappingState.SELECT, "left");
        ByteBuffer bytes = ByteBuffer.wrap(request);
        if (bytes.getLong() != 12 || bytes.get() != 4 || request.length != 13) throw new AssertionError("Request format");
        try { MappingState.request(0, MappingState.ALL, null); throw new AssertionError(); }
        catch (IllegalArgumentException expected) {}
        try { MappingState.request(1, MappingState.ALL, "left"); throw new AssertionError(); }
        catch (IllegalArgumentException expected) {}
        try { MappingState.request(1, MappingState.SELECT, null); throw new AssertionError(); }
        catch (IllegalArgumentException expected) {}
        System.out.println("Mapping status validation and request encoding passed");
    }
}
