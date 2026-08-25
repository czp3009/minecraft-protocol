import java.nio.file.Files;
import java.nio.file.Path;

class ToolProcessFixture {
    public static void main(String[] arguments) throws Exception {
        var pidFile = Path.of(arguments[1]);
        if (arguments[0].equals("child")) {
            Files.writeString(pidFile, Long.toString(ProcessHandle.current().pid()));
            System.out.println("tool-child-ready");
            Thread.currentThread().join();
            return;
        }

        Files.writeString(
                pidFile.resolveSibling("parent.pid"),
                Long.toString(ProcessHandle.current().pid())
        );
        new ProcessBuilder(
                "java",
                "-cp",
                System.getProperty("java.class.path"),
                ToolProcessFixture.class.getName(),
                "child",
                pidFile.toString()
        ).inheritIO().start();
        while (!Files.exists(pidFile)) {
            Thread.onSpinWait();
        }
        System.out.println("tool-parent-ready");
        Thread.currentThread().join();
    }
}
