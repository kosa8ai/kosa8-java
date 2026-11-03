// Smoke test against a real kosa8 daemon: create, pull, run, checkpoint, rewind, delete.
// ./test.sh && java -cp build/main examples/Smoke.java
import com.kosa8.Kosa8Client;
import com.kosa8.Options;
import com.kosa8.Sandbox;
import java.util.List;

public class Smoke {
  public static void main(String[] args) {
    Kosa8Client k = Kosa8Client.builder().build();
    System.out.println("daemon " + k.version());
    String name = "javasdk-smoke";
    long t = System.nanoTime();
    k.createSandbox(new Options.Create().name(name).egress("deny"));
    System.out.printf("created in %.1fs%n", (System.nanoTime() - t) / 1e9);
    Sandbox sb = k.sandbox(name);
    try {
      sb.pull("alpine:latest");
      var r = sb.run("alpine:latest", List.of("sh", "-c", "echo hello from kosa8-java; uname -r"));
      System.out.println("run exit=" + r.exitCode() + " out=" + r.stdout().replace("\n", "\\n"));
      System.out.println("checkpoint " + sb.checkpoint("base").checkpoint().id());
      System.out.println("rewind restore_ms " + sb.rewind(null).restoreMs());
      System.out.println("egress policy " + sb.egress().get("policy"));
    } finally {
      System.out.println("delete " + sb.delete());
    }
  }
}
