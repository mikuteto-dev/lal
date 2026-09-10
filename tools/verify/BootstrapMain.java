/** Runs the early path that died in the client log: Bootstrap -> Items -> EntityType -> Entity. */
public class BootstrapMain {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        System.out.println("Bootstrap.bootStrap completed");
        System.out.println("RESULT: PASS");
    }
}
