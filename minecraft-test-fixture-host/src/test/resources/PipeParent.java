import java.io.BufferedReader;
import java.io.InputStreamReader;

class PipeParent {
    public static void main(String[] args) throws Exception {
        var bufferedReader = new BufferedReader(new InputStreamReader(System.in));
        // Raw standard output is the inherited subprocess pipe exercised by MinecraftTestProcessTest.
        System.out.println("parent-ready");
        if (!"spawn-child-and-exit".equals(bufferedReader.readLine())) {
            throw new IllegalStateException("unexpected command");
        }
        new ProcessBuilder("java", args[0]).inheritIO().start();
    }
}
