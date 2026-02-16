package jp.mikumiku.lal.core;

import java.util.UUID;
import jp.mikumiku.lal.core.EscalationLevel;
import jp.mikumiku.lal.core.LifecycleState;

public class EntityLedgerEntry {
    public final UUID entityUUID;
    public LifecycleState state;
    public long epoch;
    public int lastSeenTick;
    public int repairBudget;
    public EscalationLevel attackLevel;
    public EscalationLevel defenseLevel;
    public int consecutiveFailures;
    public int stableTicks;
    public Float forcedHealth = null;
    public float lastKnownHealth = 20.0f;

    public EntityLedgerEntry(UUID entityUUID) {
        super();
        this.entityUUID = entityUUID;
        this.state = LifecycleState.ALIVE;
        this.epoch = 0L;
        this.lastSeenTick = 0;
        this.repairBudget = 10;
        this.attackLevel = EscalationLevel.LEVEL_0;
        this.defenseLevel = EscalationLevel.LEVEL_0;
        this.consecutiveFailures = 0;
        this.stableTicks = 0;
    }

    public void recordSuccess() {
        this.consecutiveFailures = 0;
        ++this.stableTicks;
        if (this.stableTicks >= 10) {
            this.attackLevel = this.attackLevel.deescalate();
            this.defenseLevel = this.defenseLevel.deescalate();
            this.stableTicks = 0;
        }
    }

    public void recordFailure() {
        ++this.consecutiveFailures;
        this.stableTicks = 0;
        this.attackLevel = this.attackLevel.escalate();
    }

    public void nextEpoch() {
        ++this.epoch;
    }
}

