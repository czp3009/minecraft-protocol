import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

class Fixture {
    public static void main(String[] args) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in));
        // Raw standard streams are the subprocess protocol exercised by MinecraftTestProcessTest.
        var output = new BufferedWriter(new OutputStreamWriter(System.out));
        output.write("fixture-started\n");
        output.flush();
        String command;
        while ((command = input.readLine()) != null) {
            if (command.equals("fixture-exit")) return;
            output.write(command);
            output.write("\nack:");
            output.write(command);
            output.write("\n");
            output.flush();
        }
    }
}
