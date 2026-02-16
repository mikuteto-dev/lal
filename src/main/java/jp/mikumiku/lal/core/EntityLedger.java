package jp.mikumiku.lal.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import jp.mikumiku.lal.core.EntityLedgerEntry;

public class EntityLedger {
    private static final EntityLedger INSTANCE = new EntityLedger();
    private final Map<UUID, EntityLedgerEntry> ledger = new ConcurrentHashMap<UUID, EntityLedgerEntry>();

    public EntityLedger() {
        super();
    }

    public static EntityLedger get() {
        return INSTANCE;
    }

    public EntityLedgerEntry getOrCreate(UUID uuid) {
        return this.ledger.computeIfAbsent(uuid, EntityLedgerEntry::new);
    }

    public EntityLedgerEntry get(UUID uuid) {
        return this.ledger.get(uuid);
    }

    public void remove(UUID uuid) {
        this.ledger.remove(uuid);
    }

    public boolean contains(UUID uuid) {
        return this.ledger.containsKey(uuid);
    }

    public Map<UUID, EntityLedgerEntry> getAll() {
        return this.ledger;
    }
}

