import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;

class PipeParent {
    public static void main(String[] args) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in));
        // Raw standard output is the inherited subprocess pipe exercised by MinecraftTestProcessTest.
        System.out.println("parent-ready");
        if (!"spawn-child-and-exit".equals(input.readLine())) {
            throw new IllegalStateException("unexpected command");
        }
        var java = Path.of(System.getProperty("java.home"), "bin", "java");
        new ProcessBuilder(java.toString(), args[0]).inheritIO().start();
    }
}
