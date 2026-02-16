package jp.mikumiku.lal.core;

import java.util.UUID;
import jp.mikumiku.lal.core.CombatRegistry;

public class LifePolicyEngine {
    private static boolean adminKillBypassesImmortal = false;

    public LifePolicyEngine() {
        super();
    }

    public static void setAdminKillBypassesImmortal(boolean value) {
        adminKillBypassesImmortal = value;
    }

    public static boolean requestKill(UUID targetUUID, boolean isAdminKill) {
        if (CombatRegistry.isInImmortalSet(targetUUID)) {
            if (isAdminKill && adminKillBypassesImmortal) {
                CombatRegistry.removeFromImmortalSet(targetUUID);
                CombatRegistry.addToKillSet(targetUUID);
                return true;
            }
            return false;
        }
        CombatRegistry.addToKillSet(targetUUID);
        return true;
    }

    public static boolean requestImmortal(UUID targetUUID) {
        if (CombatRegistry.isInKillSet(targetUUID)) {
            CombatRegistry.removeFromKillSet(targetUUID);
        }
        CombatRegistry.addToImmortalSet(targetUUID);
        return true;
    }
}
