package jp.mikumiku.lal.core;

public enum EscalationLevel {
    LEVEL_0,
    LEVEL_1,
    LEVEL_2,
    LEVEL_25,
    LEVEL_3;


    public EscalationLevel escalate() {
        return switch (this) {
            default -> throw new IncompatibleClassChangeError();
            case LEVEL_0 -> LEVEL_1;
            case LEVEL_1 -> LEVEL_2;
            case LEVEL_2 -> LEVEL_25;
            case LEVEL_25 -> LEVEL_3;
            case LEVEL_3 -> LEVEL_3;
        };
    }

    public EscalationLevel deescalate() {
        return switch (this) {
            default -> throw new IncompatibleClassChangeError();
            case LEVEL_0 -> LEVEL_0;
            case LEVEL_1 -> LEVEL_0;
            case LEVEL_2 -> LEVEL_1;
            case LEVEL_25 -> LEVEL_2;
            case LEVEL_3 -> LEVEL_25;
        };
    }
}

