#include <jvmti.h>
#include <jni.h>
#include <windows.h>
#include <string.h>
#include <stdint.h>

static jvmtiEnv* gJvmti = NULL;
static JavaVM* gJvm = NULL;
static volatile BOOL gRunning = TRUE;
static volatile BOOL gRetransformNeeded = FALSE;
static volatile BOOL gNeedEntityResolve = FALSE;
static volatile BOOL gNeedLivingResolve = FALSE;
static DWORD gBypassKey = TLS_OUT_OF_INDEXES;

#define UUID_SET_CAP 256

typedef struct { int64_t hi, lo; } UUID128;

typedef struct {
    UUID128 entries[UUID_SET_CAP];
    int occupied[UUID_SET_CAP];
    CRITICAL_SECTION lock;
} UUIDSet;

static UUIDSet gKillSet;
static UUIDSet gImmortalSet;
static UUIDSet gImmortalSetBackup;
static UUIDSet gDeadConfirmed;

static jmethodID gGetUUIDMID = NULL;
static jmethodID gGetMostSigMID = NULL;
static jmethodID gGetLeastSigMID = NULL;
static jmethodID gGetMaxHealthMID = NULL;
static jfieldID gHealthFID = NULL;
static jfieldID gDeadFID = NULL;
static jfieldID gDeathTimeFID = NULL;
static jfieldID gRemovalReasonFID = NULL;
static jobject gRemovalReasonKilled = NULL;
static jclass gLALEventBusClass = NULL;
static jmethodID gReRegisterAllMethod = NULL;
static volatile BOOL gEventBusResolved = FALSE;

static jmethodID gKillCallMID = NULL;
static jmethodID gDiscardCallMID = NULL;
static jmethodID gSetRemovedCallMID = NULL;

#define KILL_BURST_TICKS 1024

typedef struct {
    int64_t hi, lo;
    jobject entityRef;
    int remaining;
    int occupied;
} KillProgress;

static KillProgress gKillProgress[UUID_SET_CAP];
static CRITICAL_SECTION gKillProgressLock;

#define LAL_ENTITY_CAP 64

typedef struct {
    int64_t uuid_hi, uuid_lo;
    double posX, posY, posZ;
    float rotX, rotY;
    int tickCount;
    int occupied;
} NativeLALEntity;

static NativeLALEntity gLALEntities[LAL_ENTITY_CAP];
static CRITICAL_SECTION gLALEntityLock;
static jclass gLALEntityManagerClass = NULL;
static jmethodID gTickAllMID = NULL;
static volatile BOOL gLALEntityManagerResolved = FALSE;

#define MAX_WATCHED 16

typedef struct {
    jmethodID mid;
    int forImmortal;
} WatchedMethod;

static WatchedMethod gWatched[MAX_WATCHED];
static int gWatchedCount = 0;
static CRITICAL_SECTION gWatchedLock;

static const char* LAL_TARGETS[] = {
    "net/minecraft/world/entity/Entity",
    "net/minecraft/world/entity/LivingEntity",
    "net/minecraft/world/entity/player/Player",
    "net/minecraft/server/level/ServerPlayer",
    "net/minecraft/server/level/ServerLevel",
    NULL
};

static void UUIDSet_init(UUIDSet* s) {
    memset(s->entries, 0, sizeof(s->entries));
    memset(s->occupied, 0, sizeof(s->occupied));
    InitializeCriticalSection(&s->lock);
}

static int UUIDSet_contains(UUIDSet* s, int64_t hi, int64_t lo) {
    EnterCriticalSection(&s->lock);
    int result = 0;
    for (int i = 0; i < UUID_SET_CAP; i++) {
        if (s->occupied[i] && s->entries[i].hi == hi && s->entries[i].lo == lo) {
            result = 1;
            break;
        }
    }
    LeaveCriticalSection(&s->lock);
    return result;
}

static void UUIDSet_add(UUIDSet* s, int64_t hi, int64_t lo) {
    EnterCriticalSection(&s->lock);
    for (int i = 0; i < UUID_SET_CAP; i++) {
        if (s->occupied[i] && s->entries[i].hi == hi && s->entries[i].lo == lo) {
            LeaveCriticalSection(&s->lock);
            return;
        }
    }
    for (int i = 0; i < UUID_SET_CAP; i++) {
        if (!s->occupied[i]) {
            s->entries[i].hi = hi;
            s->entries[i].lo = lo;
            s->occupied[i] = 1;
            break;
        }
    }
    LeaveCriticalSection(&s->lock);
}

static void UUIDSet_remove(UUIDSet* s, int64_t hi, int64_t lo) {
    EnterCriticalSection(&s->lock);
    for (int i = 0; i < UUID_SET_CAP; i++) {
        if (s->occupied[i] && s->entries[i].hi == hi && s->entries[i].lo == lo) {
            s->occupied[i] = 0;
            break;
        }
    }
    LeaveCriticalSection(&s->lock);
}

static void KillProgress_register(JNIEnv* env, int64_t hi, int64_t lo, jobject entity) {
    EnterCriticalSection(&gKillProgressLock);
    for (int i = 0; i < UUID_SET_CAP; i++) {
        if (gKillProgress[i].occupied && gKillProgress[i].hi == hi && gKillProgress[i].lo == lo) {
            gKillProgress[i].remaining = KILL_BURST_TICKS;
            LeaveCriticalSection(&gKillProgressLock);
            return;
        }
    }
    for (int i = 0; i < UUID_SET_CAP; i++) {
        if (!gKillProgress[i].occupied) {
            gKillProgress[i].hi = hi;
            gKillProgress[i].lo = lo;
            gKillProgress[i].entityRef = (*env)->NewGlobalRef(env, entity);
            gKillProgress[i].remaining = KILL_BURST_TICKS;
            gKillProgress[i].occupied = 1;
            break;
        }
    }
    LeaveCriticalSection(&gKillProgressLock);
}

static void KillProgress_clear(JNIEnv* env, int64_t hi, int64_t lo) {
    EnterCriticalSection(&gKillProgressLock);
    for (int i = 0; i < UUID_SET_CAP; i++) {
        if (gKillProgress[i].occupied && gKillProgress[i].hi == hi && gKillProgress[i].lo == lo) {
            if (gKillProgress[i].entityRef) (*env)->DeleteGlobalRef(env, gKillProgress[i].entityRef);
            gKillProgress[i].occupied = 0;
            gKillProgress[i].entityRef = NULL;
            break;
        }
    }
    LeaveCriticalSection(&gKillProgressLock);
}

static int isBypass(void) {
    if (gBypassKey == TLS_OUT_OF_INDEXES) return 0;
    return TlsGetValue(gBypassKey) != NULL;
}

static int getEntityUUID(JNIEnv* env, jobject entity, int64_t* hi, int64_t* lo) {
    if (!gGetUUIDMID || !gGetMostSigMID || !gGetLeastSigMID) return 0;
    jobject uuidObj = (*env)->CallObjectMethod(env, entity, gGetUUIDMID);
    if (!uuidObj || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return 0;
    }
    *hi = (*env)->CallLongMethod(env, uuidObj, gGetMostSigMID);
    *lo = (*env)->CallLongMethod(env, uuidObj, gGetLeastSigMID);
    (*env)->DeleteLocalRef(env, uuidObj);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return 0;
    }
    return 1;
}

static void addWatched(jmethodID mid, int forImmortal) {
    if (!mid) return;
    EnterCriticalSection(&gWatchedLock);
    if (gWatchedCount < MAX_WATCHED) {
        gWatched[gWatchedCount].mid = mid;
        gWatched[gWatchedCount].forImmortal = forImmortal;
        gWatchedCount++;
    }
    LeaveCriticalSection(&gWatchedLock);
}

static jfieldID findFieldID(JNIEnv* env, jclass cls, const char* sig, const char* name1, const char* name2) {
    jfieldID fid = (*env)->GetFieldID(env, cls, name1, sig);
    if (fid) { (*env)->ExceptionClear(env); return fid; }
    (*env)->ExceptionClear(env);
    if (name2) {
        fid = (*env)->GetFieldID(env, cls, name2, sig);
        if (fid) { (*env)->ExceptionClear(env); return fid; }
        (*env)->ExceptionClear(env);
    }
    jint count; jfieldID* fields;
    if ((*gJvmti)->GetClassFields(gJvmti, cls, &count, &fields) != JVMTI_ERROR_NONE) return NULL;
    jfieldID result = NULL;
    for (int i = 0; i < count && !result; i++) {
        char* fn; char* fs;
        if ((*gJvmti)->GetFieldName(gJvmti, cls, fields[i], &fn, &fs, NULL) != JVMTI_ERROR_NONE) continue;
        if (fs && strcmp(fs, sig) == 0 && fn) {
            if ((name1 && strstr(fn, name1)) || (name2 && strstr(fn, name2)))
                result = fields[i];
        }
        (*gJvmti)->Deallocate(gJvmti, (unsigned char*)fn);
        (*gJvmti)->Deallocate(gJvmti, (unsigned char*)fs);
    }
    (*gJvmti)->Deallocate(gJvmti, (unsigned char*)fields);
    return result;
}

static jmethodID findMethodID(JNIEnv* env, jclass cls, const char* desc, const char* name1, const char* name2) {
    jmethodID mid = (*env)->GetMethodID(env, cls, name1, desc);
    if (mid) { (*env)->ExceptionClear(env); return mid; }
    (*env)->ExceptionClear(env);
    if (name2) {
        mid = (*env)->GetMethodID(env, cls, name2, desc);
        if (mid) { (*env)->ExceptionClear(env); return mid; }
        (*env)->ExceptionClear(env);
    }
    jint count; jmethodID* methods;
    if ((*gJvmti)->GetClassMethods(gJvmti, cls, &count, &methods) != JVMTI_ERROR_NONE) return NULL;
    jmethodID result = NULL;
    for (int i = 0; i < count && !result; i++) {
        char* mn; char* ms;
        if ((*gJvmti)->GetMethodName(gJvmti, methods[i], &mn, &ms, NULL) != JVMTI_ERROR_NONE) continue;
        if (ms && strcmp(ms, desc) == 0 && mn) {
            if ((name1 && strcmp(mn, name1) == 0) || (name2 && strcmp(mn, name2) == 0))
                result = methods[i];
        }
        (*gJvmti)->Deallocate(gJvmti, (unsigned char*)mn);
        (*gJvmti)->Deallocate(gJvmti, (unsigned char*)ms);
    }
    (*gJvmti)->Deallocate(gJvmti, (unsigned char*)methods);
    return result;
}

static void resolveEntityMembers(JNIEnv* env) {
    if (gGetUUIDMID) return;
    jclass cls = (*env)->FindClass(env, "net/minecraft/world/entity/Entity");
    if (!cls) { (*env)->ExceptionClear(env); return; }

    gGetUUIDMID = findMethodID(env, cls, "()Ljava/util/UUID;", "m_20148_", "getUUID");

    jclass uuidCls = (*env)->FindClass(env, "java/util/UUID");
    if (uuidCls) {
        gGetMostSigMID = (*env)->GetMethodID(env, uuidCls, "getMostSignificantBits", "()J");
        gGetLeastSigMID = (*env)->GetMethodID(env, uuidCls, "getLeastSignificantBits", "()J");
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, uuidCls);
    }

    jmethodID mid;
    mid = findMethodID(env, cls, "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", "m_142467_", "setRemoved");
    if (mid) { (*gJvmti)->SetBreakpoint(gJvmti, mid, 0); addWatched(mid, 1); gSetRemovedCallMID = mid; }
    mid = findMethodID(env, cls, "()V", "m_146870_", "discard");
    if (mid) { (*gJvmti)->SetBreakpoint(gJvmti, mid, 0); addWatched(mid, 1); gDiscardCallMID = mid; }
    mid = findMethodID(env, cls, "()V", "m_6074_", "kill");
    if (mid) { (*gJvmti)->SetBreakpoint(gJvmti, mid, 0); addWatched(mid, 1); gKillCallMID = mid; }

    (*env)->DeleteLocalRef(env, cls);
}

static void resolveLivingEntityMembers(JNIEnv* env) {
    if (gHealthFID) return;
    jclass cls = (*env)->FindClass(env, "net/minecraft/world/entity/LivingEntity");
    if (!cls) { (*env)->ExceptionClear(env); return; }

    gHealthFID    = findFieldID(env, cls, "F", "f_20958_", "health");
    gDeadFID      = findFieldID(env, cls, "Z", "f_20960_", "dead");
    gDeathTimeFID = findFieldID(env, cls, "I", "f_20962_", "deathTime");

    if (gHealthFID)    (*gJvmti)->SetFieldModificationWatch(gJvmti, cls, gHealthFID);
    if (gDeadFID)      (*gJvmti)->SetFieldModificationWatch(gJvmti, cls, gDeadFID);
    if (gDeathTimeFID) (*gJvmti)->SetFieldModificationWatch(gJvmti, cls, gDeathTimeFID);

    gGetMaxHealthMID = findMethodID(env, cls, "()F", "m_21233_", "getMaxHealth");

    jmethodID mid;
    mid = findMethodID(env, cls, "(Lnet/minecraft/world/damagesource/DamageSource;)V", "m_6667_", "die");
    if (mid) { (*gJvmti)->SetBreakpoint(gJvmti, mid, 0); addWatched(mid, 1); }
    mid = findMethodID(env, cls, "()V", "m_6153_", "tickDeath");
    if (mid) { (*gJvmti)->SetBreakpoint(gJvmti, mid, 0); addWatched(mid, 1); }
    mid = findMethodID(env, cls, "(F)V", "m_5634_", "heal");
    if (mid) { (*gJvmti)->SetBreakpoint(gJvmti, mid, 0); addWatched(mid, 0); }

    (*env)->DeleteLocalRef(env, cls);

    if (!gRemovalReasonFID) {
        jclass entityCls = (*env)->FindClass(env, "net/minecraft/world/entity/Entity");
        if (entityCls) {
            gRemovalReasonFID = findFieldID(env, entityCls, "Lnet/minecraft/world/entity/Entity$RemovalReason;", "f_20876_", "removalReason");
            if (gRemovalReasonFID) {
                (*gJvmti)->SetFieldModificationWatch(gJvmti, entityCls, gRemovalReasonFID);
            }
            jclass rrClass = (*env)->FindClass(env, "net/minecraft/world/entity/Entity$RemovalReason");
            if (rrClass) {
                (*env)->ExceptionClear(env);
                jfieldID killedFID = (*env)->GetStaticFieldID(env, rrClass, "KILLED", "Lnet/minecraft/world/entity/Entity$RemovalReason;");
                if (!killedFID) { (*env)->ExceptionClear(env); }
                if (killedFID) {
                    jobject killedVal = (*env)->GetStaticObjectField(env, rrClass, killedFID);
                    if (killedVal && !(*env)->ExceptionCheck(env)) {
                        gRemovalReasonKilled = (*env)->NewGlobalRef(env, killedVal);
                        (*env)->DeleteLocalRef(env, killedVal);
                    } else { (*env)->ExceptionClear(env); }
                }
                (*env)->DeleteLocalRef(env, rrClass);
            } else { (*env)->ExceptionClear(env); }
            (*env)->DeleteLocalRef(env, entityCls);
        } else { (*env)->ExceptionClear(env); }
    }
}

JNIEXPORT jboolean JNICALL
Java_jp_mikumiku_lal_core_CombatRegistry_nativeIsInKillSet(JNIEnv* env, jclass cls, jlong hi, jlong lo) {
    return UUIDSet_contains(&gKillSet, hi, lo) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_jp_mikumiku_lal_core_CombatRegistry_nativeAddToKillSet(JNIEnv* env, jclass cls, jlong hi, jlong lo) {
    UUIDSet_remove(&gImmortalSet, hi, lo);
    UUIDSet_remove(&gKillSet, hi, lo);
    UUIDSet_add(&gKillSet, hi, lo);
}

JNIEXPORT void JNICALL
Java_jp_mikumiku_lal_core_CombatRegistry_nativeRemoveFromKillSet(JNIEnv* env, jclass cls, jlong hi, jlong lo) {
    UUIDSet_remove(&gKillSet, hi, lo);
}

JNIEXPORT jboolean JNICALL
Java_jp_mikumiku_lal_core_CombatRegistry_nativeIsInImmortalSet(JNIEnv* env, jclass cls, jlong hi, jlong lo) {
    return UUIDSet_contains(&gImmortalSet, hi, lo) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_jp_mikumiku_lal_core_CombatRegistry_nativeAddToImmortalSet(JNIEnv* env, jclass cls, jlong hi, jlong lo) {
    UUIDSet_remove(&gKillSet, hi, lo);
    UUIDSet_remove(&gDeadConfirmed, hi, lo);
    UUIDSet_add(&gImmortalSet, hi, lo);
    UUIDSet_add(&gImmortalSetBackup, hi, lo);
}

JNIEXPORT void JNICALL
Java_jp_mikumiku_lal_core_CombatRegistry_nativeRemoveFromImmortalSet(JNIEnv* env, jclass cls, jlong hi, jlong lo) {
    UUIDSet_remove(&gImmortalSet, hi, lo);
    UUIDSet_remove(&gImmortalSetBackup, hi, lo);
}

JNIEXPORT jboolean JNICALL
Java_jp_mikumiku_lal_core_CombatRegistry_nativeIsDeadConfirmed(JNIEnv* env, jclass cls, jlong hi, jlong lo) {
    return UUIDSet_contains(&gDeadConfirmed, hi, lo) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_jp_mikumiku_lal_core_CombatRegistry_nativeConfirmDead(JNIEnv* env, jclass cls, jlong hi, jlong lo) {
    UUIDSet_remove(&gKillSet, hi, lo);
    UUIDSet_add(&gDeadConfirmed, hi, lo);
}

JNIEXPORT void JNICALL
Java_jp_mikumiku_lal_core_CombatRegistry_nativeClearDeadConfirmed(JNIEnv* env, jclass cls, jlong hi, jlong lo) {
    UUIDSet_remove(&gDeadConfirmed, hi, lo);
}

JNIEXPORT void JNICALL
Java_jp_mikumiku_lal_core_CombatRegistry_nativeSyncImmortalFromBackup(JNIEnv* env, jclass cls) {
    UUID128 temp[UUID_SET_CAP];
    int tempCount = 0;
    EnterCriticalSection(&gImmortalSetBackup.lock);
    for (int i = 0; i < UUID_SET_CAP && tempCount < UUID_SET_CAP; i++) {
        if (gImmortalSetBackup.occupied[i]) temp[tempCount++] = gImmortalSetBackup.entries[i];
    }
    LeaveCriticalSection(&gImmortalSetBackup.lock);
    for (int i = 0; i < tempCount; i++) {
        if (!UUIDSet_contains(&gImmortalSet, temp[i].hi, temp[i].lo))
            UUIDSet_add(&gImmortalSet, temp[i].hi, temp[i].lo);
    }
}

JNIEXPORT void JNICALL
Java_jp_mikumiku_lal_transformer_EntityMethodHooks_nativeSetBypass(JNIEnv* env, jclass cls, jboolean bypass) {
    if (gBypassKey != TLS_OUT_OF_INDEXES)
        TlsSetValue(gBypassKey, bypass ? (LPVOID)1 : NULL);
}

static void JNICALL ClassFileLoadHookCB(
    jvmtiEnv* jvmti_env, JNIEnv* jni_env,
    jclass class_being_redefined, jobject loader,
    const char* name, jobject protection_domain,
    jint class_data_len, const unsigned char* class_data,
    jint* new_class_data_len, unsigned char** new_class_data)
{
    if (new_class_data) *new_class_data = NULL;
    if (new_class_data_len) *new_class_data_len = 0;
    if (!name) return;
    if (class_being_redefined) {
        for (int i = 0; LAL_TARGETS[i]; i++) {
            if (strcmp(name, LAL_TARGETS[i]) == 0) { gRetransformNeeded = TRUE; return; }
        }
        return;
    }
    if (strcmp(name, "net/minecraft/world/entity/Entity") == 0) gNeedEntityResolve = TRUE;
    if (strcmp(name, "net/minecraft/world/entity/LivingEntity") == 0) gNeedLivingResolve = TRUE;
}

static void JNICALL FieldModificationCB(
    jvmtiEnv* jvmti, JNIEnv* env, jthread thread,
    jmethodID method, jlocation location,
    jclass field_class, jobject object,
    jfieldID field, char sig_type, jvalue new_value)
{
    if (!object || isBypass()) return;
    int64_t hi, lo;
    if (!getEntityUUID(env, object, &hi, &lo)) return;

    if (field == gHealthFID) {
        if (UUIDSet_contains(&gKillSet, hi, lo)) {
            KillProgress_register(env, hi, lo, object);
            (*env)->SetFloatField(env, object, gHealthFID, 0.0f);
        } else if (UUIDSet_contains(&gImmortalSet, hi, lo)) {
            float maxHp = 20.0f;
            if (gGetMaxHealthMID) {
                float v = (*env)->CallFloatMethod(env, object, gGetMaxHealthMID);
                if (!(*env)->ExceptionCheck(env) && v > 0.0f) maxHp = v;
                else (*env)->ExceptionClear(env);
            }
            if (new_value.f < maxHp)
                (*env)->SetFloatField(env, object, gHealthFID, maxHp);
        }
    } else if (field == gDeadFID) {
        if (UUIDSet_contains(&gImmortalSet, hi, lo) && new_value.z != 0)
            (*env)->SetBooleanField(env, object, gDeadFID, JNI_FALSE);
    } else if (field == gDeathTimeFID) {
        if (UUIDSet_contains(&gImmortalSet, hi, lo) && new_value.i > 0)
            (*env)->SetIntField(env, object, gDeathTimeFID, 0);
    } else if (field == gRemovalReasonFID) {
        if (UUIDSet_contains(&gImmortalSet, hi, lo)) {
            if (new_value.l != NULL) {
                (*env)->SetObjectField(env, object, gRemovalReasonFID, NULL);
            }
        } else if (UUIDSet_contains(&gKillSet, hi, lo)) {
            if (new_value.l == NULL || (gRemovalReasonKilled != NULL && !(*env)->IsSameObject(env, new_value.l, gRemovalReasonKilled))) {
                if (gRemovalReasonKilled != NULL) {
                    (*env)->SetObjectField(env, object, gRemovalReasonFID, gRemovalReasonKilled);
                }
            }
        }
    }
}

static void JNICALL BreakpointCB(
    jvmtiEnv* jvmti, JNIEnv* env, jthread thread,
    jmethodID method, jlocation location)
{
    if (isBypass()) return;

    int forImmortal = -1;
    EnterCriticalSection(&gWatchedLock);
    for (int i = 0; i < gWatchedCount; i++) {
        if (gWatched[i].mid == method) { forImmortal = gWatched[i].forImmortal; break; }
    }
    LeaveCriticalSection(&gWatchedLock);
    if (forImmortal < 0) return;

    jobject thisObj = NULL;
    if ((*jvmti)->GetLocalObject(jvmti, thread, 0, 0, &thisObj) != JVMTI_ERROR_NONE || !thisObj) return;

    int64_t hi, lo;
    int got = getEntityUUID(env, thisObj, &hi, &lo);
    (*env)->DeleteLocalRef(env, thisObj);
    if (!got) return;

    if (forImmortal && UUIDSet_contains(&gImmortalSet, hi, lo))
        (*jvmti)->ForceEarlyReturnVoid(jvmti, thread);
    else if (!forImmortal && UUIDSet_contains(&gKillSet, hi, lo))
        (*jvmti)->ForceEarlyReturnVoid(jvmti, thread);
    else if (forImmortal && UUIDSet_contains(&gKillSet, hi, lo)) {
        jobject obj2 = NULL;
        if ((*jvmti)->GetLocalObject(jvmti, thread, 0, 0, &obj2) == JVMTI_ERROR_NONE && obj2) {
            KillProgress_register(env, hi, lo, obj2);
            (*env)->DeleteLocalRef(env, obj2);
        }
    }
}

static DWORD WINAPI EnforcementThread(LPVOID param) {
    if (!gJvm || !gJvmti) return 0;
    Sleep(5000);

    JNIEnv* env = NULL;
    if ((*gJvm)->AttachCurrentThreadAsDaemon(gJvm, (void**)&env, NULL) != JNI_OK || !env) return 0;

    jclass agentClass = (*env)->FindClass(env, "jp/mikumiku/lal/agent/LALAgent");
    if (!agentClass) { (*env)->ExceptionClear(env); (*gJvm)->DetachCurrentThread(gJvm); return 0; }

    jmethodID retransformMethod = (*env)->GetStaticMethodID(env, agentClass, "retransformTargetClasses", "()V");
    if (!retransformMethod) { (*env)->ExceptionClear(env); (*gJvm)->DetachCurrentThread(gJvm); return 0; }

    int loopCount = 0;
    while (gRunning) {
        Sleep(50);

        if (gNeedEntityResolve && !gGetUUIDMID) {
            resolveEntityMembers(env);
            gNeedEntityResolve = FALSE;
        }
        if (gNeedLivingResolve && !gHealthFID) {
            resolveLivingEntityMembers(env);
            gNeedLivingResolve = FALSE;
        }

        EnterCriticalSection(&gKillProgressLock);
        for (int i = 0; i < UUID_SET_CAP; i++) {
            if (!gKillProgress[i].occupied) continue;
            if (gKillProgress[i].remaining <= 0) {
                if (gKillProgress[i].entityRef) (*env)->DeleteGlobalRef(env, gKillProgress[i].entityRef);
                gKillProgress[i].occupied = 0;
                gKillProgress[i].entityRef = NULL;
                continue;
            }
            if (!UUIDSet_contains(&gKillSet, gKillProgress[i].hi, gKillProgress[i].lo)) {
                if (gKillProgress[i].entityRef) (*env)->DeleteGlobalRef(env, gKillProgress[i].entityRef);
                gKillProgress[i].occupied = 0;
                gKillProgress[i].entityRef = NULL;
                continue;
            }
            jobject entity = gKillProgress[i].entityRef;
            if (!entity) { gKillProgress[i].occupied = 0; continue; }
            TlsSetValue(gBypassKey, (LPVOID)1);
            if (gHealthFID) {
                (*env)->SetFloatField(env, entity, gHealthFID, 0.0f);
                (*env)->ExceptionClear(env);
            }
            if (gDeadFID) {
                (*env)->SetBooleanField(env, entity, gDeadFID, JNI_TRUE);
                (*env)->ExceptionClear(env);
            }
            if (gDeathTimeFID) {
                (*env)->SetIntField(env, entity, gDeathTimeFID, 20);
                (*env)->ExceptionClear(env);
            }
            if (gRemovalReasonFID && gRemovalReasonKilled) {
                (*env)->SetObjectField(env, entity, gRemovalReasonFID, gRemovalReasonKilled);
                (*env)->ExceptionClear(env);
            }
            if (gKillCallMID) {
                (*env)->CallVoidMethod(env, entity, gKillCallMID);
                (*env)->ExceptionClear(env);
            }
            if (gDiscardCallMID) {
                (*env)->CallVoidMethod(env, entity, gDiscardCallMID);
                (*env)->ExceptionClear(env);
            }
            if (gSetRemovedCallMID && gRemovalReasonKilled) {
                (*env)->CallVoidMethod(env, entity, gSetRemovedCallMID, gRemovalReasonKilled);
                (*env)->ExceptionClear(env);
            }
            TlsSetValue(gBypassKey, NULL);
            gKillProgress[i].remaining--;
        }
        LeaveCriticalSection(&gKillProgressLock);

        if (gRetransformNeeded) {
            gRetransformNeeded = FALSE;
            (*env)->CallStaticVoidMethod(env, agentClass, retransformMethod);
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        }

        if (!gLALEntityManagerResolved) {
            jclass lemClass = (*env)->FindClass(env, "jp/mikumiku/lal/entity/LALEntityManager");
            if (lemClass) {
                jmethodID taMID = (*env)->GetStaticMethodID(env, lemClass, "tickAll", "()V");
                if (taMID) {
                    gLALEntityManagerClass = (*env)->NewGlobalRef(env, lemClass);
                    gTickAllMID = taMID;
                    gLALEntityManagerResolved = TRUE;
                }
                (*env)->DeleteLocalRef(env, lemClass);
                if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
            } else { (*env)->ExceptionClear(env); }
        }

        if (gLALEntityManagerClass && gTickAllMID) {
            EnterCriticalSection(&gLALEntityLock);
            int entityCount = 0;
            for (int i = 0; i < LAL_ENTITY_CAP; i++) {
                if (gLALEntities[i].occupied) entityCount++;
            }
            LeaveCriticalSection(&gLALEntityLock);
            if (entityCount > 0) {
                (*env)->CallStaticVoidMethod(env, gLALEntityManagerClass, gTickAllMID);
                if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
            }
        }

        if (!gEventBusResolved) {
            jclass ebClass = (*env)->FindClass(env, "jp/mikumiku/lal/core/LALEventBus");
            if (ebClass) {
                jmethodID rrMethod = (*env)->GetStaticMethodID(env, ebClass, "reRegisterAll", "()V");
                if (rrMethod) {
                    gLALEventBusClass = (*env)->NewGlobalRef(env, ebClass);
                    gReRegisterAllMethod = rrMethod;
                    gEventBusResolved = TRUE;
                }
                (*env)->DeleteLocalRef(env, ebClass);
                if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
            } else { (*env)->ExceptionClear(env); }
        }

        if (gLALEventBusClass && gReRegisterAllMethod) {
            (*env)->CallStaticVoidMethod(env, gLALEventBusClass, gReRegisterAllMethod);
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        }

        if (++loopCount >= 200) {
            loopCount = 0;
            (*env)->CallStaticVoidMethod(env, agentClass, retransformMethod);
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        }
    }

    (*gJvm)->DetachCurrentThread(gJvm);
    return 0;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    if (!vm) return JNI_VERSION_1_8;
    gJvm = vm;

    jvmtiEnv* jvmti = NULL;
    if ((*vm)->GetEnv(vm, (void**)&jvmti, JVMTI_VERSION_1_2) != JNI_OK || !jvmti) return JNI_VERSION_1_8;
    gJvmti = jvmti;

    UUIDSet_init(&gKillSet);
    UUIDSet_init(&gImmortalSet);
    UUIDSet_init(&gImmortalSetBackup);
    UUIDSet_init(&gDeadConfirmed);
    InitializeCriticalSection(&gWatchedLock);
    memset(gKillProgress, 0, sizeof(gKillProgress));
    InitializeCriticalSection(&gKillProgressLock);
    memset(gLALEntities, 0, sizeof(gLALEntities));
    InitializeCriticalSection(&gLALEntityLock);
    gBypassKey = TlsAlloc();

    jvmtiCapabilities caps;
    memset(&caps, 0, sizeof(caps));
    caps.can_retransform_classes = 1;
    caps.can_generate_all_class_hook_events = 1;
    caps.can_generate_field_modification_events = 1;
    caps.can_generate_breakpoint_events = 1;
    caps.can_force_early_return = 1;
    caps.can_access_local_variables = 1;
    if ((*jvmti)->AddCapabilities(jvmti, &caps) != JVMTI_ERROR_NONE) return JNI_VERSION_1_8;

    jvmtiEventCallbacks callbacks;
    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.ClassFileLoadHook = ClassFileLoadHookCB;
    callbacks.FieldModification = FieldModificationCB;
    callbacks.Breakpoint = BreakpointCB;
    if ((*jvmti)->SetEventCallbacks(jvmti, &callbacks, sizeof(callbacks)) != JVMTI_ERROR_NONE) return JNI_VERSION_1_8;

    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, NULL);
    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_FIELD_MODIFICATION, NULL);
    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_BREAKPOINT, NULL);

    HANDLE thread = CreateThread(NULL, 0, EnforcementThread, NULL, 0, NULL);
    if (thread) CloseHandle(thread);

    return JNI_VERSION_1_8;
}

static void jni_toLower(char* dst, const char* src, int maxLen) {
    int i;
    for (i = 0; i < maxLen - 1 && src[i]; i++)
        dst[i] = (src[i] >= 'A' && src[i] <= 'Z') ? (char)(src[i] + 32) : src[i];
    dst[i] = '\0';
}

static const char* TMS_ALIVE_HINTS[] = {"alive","active","valid","enabled","enable","spawned","isalive","isactive"};
static const char* TMS_DEAD_HINTS[] = {"dead","removed","disabled","destroyed","killed","isdead","isremoved"};
static const char* TMS_HEALTH_HINTS[] = {"health","hp","life","hitpoints","currenthealth","currenthp"};
#define N_ALIVE 8
#define N_DEAD 7
#define N_HEALTH 6

static int tmsHintMatch(const char* low, const char** hints, int n) {
    for (int i = 0; i < n; i++) if (strstr(low, hints[i])) return 1;
    return 0;
}

JNIEXPORT jobjectArray JNICALL
Java_jp_mikumiku_lal_enforcement_ObjectLinker_nativeGetFieldValues(
    JNIEnv* env, jclass cls, jobject target)
{
    if (!gJvmti || !target) return NULL;
    (*env)->EnsureLocalCapacity(env, 512);
    #define FV_MAX 256
    jobject vals[FV_MAX];
    int cnt = 0;
    jclass objCls = (*env)->FindClass(env, "java/lang/Object");
    if (!objCls) { (*env)->ExceptionClear(env); return NULL; }
    jclass cur = (*env)->GetObjectClass(env, target);
    while (cur && !(*env)->IsSameObject(env, cur, objCls) && cnt < FV_MAX) {
        jint fc = 0; jfieldID* fids = NULL;
        if ((*gJvmti)->GetClassFields(gJvmti, cur, &fc, &fids) == JVMTI_ERROR_NONE) {
            for (int i = 0; i < fc && cnt < FV_MAX; i++) {
                jint m = 0;
                (*gJvmti)->GetFieldModifiers(gJvmti, cur, fids[i], &m);
                if (m & 0x0008) continue;
                char* s = NULL;
                (*gJvmti)->GetFieldName(gJvmti, cur, fids[i], NULL, &s, NULL);
                int ok = (s && (s[0]=='L' || (s[0]=='[' && (s[1]=='L' || s[1]=='['))));
                if (s) (*gJvmti)->Deallocate(gJvmti, (unsigned char*)s);
                if (!ok) continue;
                __try {
                    if ((*env)->PushLocalFrame(env, 4) < 0) {
                        (*env)->ExceptionClear(env);
                        continue;
                    }
                    jobject v = (*env)->GetObjectField(env, target, fids[i]);
                    if ((*env)->ExceptionCheck(env)) {
                        (*env)->ExceptionClear(env);
                        (*env)->PopLocalFrame(env, NULL);
                        continue;
                    }
                    if (v) {
                        jclass vCls = (*env)->GetObjectClass(env, v);
                        if (vCls && !(*env)->ExceptionCheck(env)) {
                            (*env)->DeleteLocalRef(env, vCls);
                            jobject promoted = (*env)->PopLocalFrame(env, v);
                            if (promoted) vals[cnt++] = promoted;
                        } else {
                            (*env)->ExceptionClear(env);
                            (*env)->PopLocalFrame(env, NULL);
                        }
                    } else {
                        (*env)->PopLocalFrame(env, NULL);
                    }
                } __except(EXCEPTION_EXECUTE_HANDLER) {
                    (*env)->ExceptionClear(env);
                    __try { (*env)->PopLocalFrame(env, NULL); } __except(EXCEPTION_EXECUTE_HANDLER) {}
                    continue;
                }
            }
            (*gJvmti)->Deallocate(gJvmti, (unsigned char*)fids);
        }
        __try {
            jclass sup = (*env)->GetSuperclass(env, cur);
            (*env)->DeleteLocalRef(env, cur);
            cur = sup;
        } __except(EXCEPTION_EXECUTE_HANDLER) {
            (*env)->ExceptionClear(env);
            cur = NULL;
        }
    }
    if (cur) { __try { (*env)->DeleteLocalRef(env, cur); } __except(EXCEPTION_EXECUTE_HANDLER) {} }
    jobjectArray res = (*env)->NewObjectArray(env, cnt, objCls, NULL);
    for (int i = 0; i < cnt; i++) {
        __try {
            (*env)->SetObjectArrayElement(env, res, i, vals[i]);
            (*env)->DeleteLocalRef(env, vals[i]);
        } __except(EXCEPTION_EXECUTE_HANDLER) {
            continue;
        }
    }
    (*env)->DeleteLocalRef(env, objCls);
    return res;
    #undef FV_MAX
}

JNIEXPORT jobjectArray JNICALL
Java_jp_mikumiku_lal_enforcement_ObjectLinker_nativeGetStaticFieldValues(
    JNIEnv* env, jclass cls, jclass targetClass)
{
    if (!gJvmti || !targetClass) return NULL;
    (*env)->EnsureLocalCapacity(env, 512);
    #define SFV_MAX 256
    jobject vals[SFV_MAX];
    int cnt = 0;
    jclass objCls = (*env)->FindClass(env, "java/lang/Object");
    if (!objCls) { (*env)->ExceptionClear(env); return NULL; }
    jclass cur = (jclass)(*env)->NewLocalRef(env, targetClass);
    while (cur && !(*env)->IsSameObject(env, cur, objCls) && cnt < SFV_MAX) {
        jint fc = 0; jfieldID* fids = NULL;
        if ((*gJvmti)->GetClassFields(gJvmti, cur, &fc, &fids) == JVMTI_ERROR_NONE) {
            for (int i = 0; i < fc && cnt < SFV_MAX; i++) {
                jint m = 0;
                (*gJvmti)->GetFieldModifiers(gJvmti, cur, fids[i], &m);
                if (!(m & 0x0008)) continue;
                char* s = NULL;
                (*gJvmti)->GetFieldName(gJvmti, cur, fids[i], NULL, &s, NULL);
                int ok = (s && (s[0]=='L' || (s[0]=='[' && (s[1]=='L' || s[1]=='['))));
                if (s) (*gJvmti)->Deallocate(gJvmti, (unsigned char*)s);
                if (!ok) continue;
                __try {
                    if ((*env)->PushLocalFrame(env, 4) < 0) {
                        (*env)->ExceptionClear(env);
                        continue;
                    }
                    jobject v = (*env)->GetStaticObjectField(env, cur, fids[i]);
                    if ((*env)->ExceptionCheck(env)) {
                        (*env)->ExceptionClear(env);
                        (*env)->PopLocalFrame(env, NULL);
                        continue;
                    }
                    if (v) {
                        jclass vCls = (*env)->GetObjectClass(env, v);
                        if (vCls && !(*env)->ExceptionCheck(env)) {
                            (*env)->DeleteLocalRef(env, vCls);
                            jobject promoted = (*env)->PopLocalFrame(env, v);
                            if (promoted) vals[cnt++] = promoted;
                        } else {
                            (*env)->ExceptionClear(env);
                            (*env)->PopLocalFrame(env, NULL);
                        }
                    } else {
                        (*env)->PopLocalFrame(env, NULL);
                    }
                } __except(EXCEPTION_EXECUTE_HANDLER) {
                    (*env)->ExceptionClear(env);
                    __try { (*env)->PopLocalFrame(env, NULL); } __except(EXCEPTION_EXECUTE_HANDLER) {}
                    continue;
                }
            }
            (*gJvmti)->Deallocate(gJvmti, (unsigned char*)fids);
        }
        __try {
            jclass sup = (*env)->GetSuperclass(env, cur);
            (*env)->DeleteLocalRef(env, cur);
            cur = sup;
        } __except(EXCEPTION_EXECUTE_HANDLER) {
            (*env)->ExceptionClear(env);
            cur = NULL;
        }
    }
    if (cur) { __try { (*env)->DeleteLocalRef(env, cur); } __except(EXCEPTION_EXECUTE_HANDLER) {} }
    jobjectArray res = (*env)->NewObjectArray(env, cnt, objCls, NULL);
    for (int i = 0; i < cnt; i++) {
        __try {
            (*env)->SetObjectArrayElement(env, res, i, vals[i]);
            (*env)->DeleteLocalRef(env, vals[i]);
        } __except(EXCEPTION_EXECUTE_HANDLER) {
            continue;
        }
    }
    (*env)->DeleteLocalRef(env, objCls);
    return res;
    #undef SFV_MAX
}

JNIEXPORT jobjectArray JNICALL
Java_jp_mikumiku_lal_enforcement_ObjectLinker_nativeExtractElements(
    JNIEnv* env, jclass cls, jobject container)
{
    if (!gJvmti || !container) return NULL;
    jclass containerCls = (*env)->GetObjectClass(env, container);
    if (!containerCls) return NULL;
    jclass classCls = (*env)->FindClass(env, "java/lang/Class");
    jmethodID isArrM = (*env)->GetMethodID(env, classCls, "isArray", "()Z");
    jboolean isArr = (*env)->CallBooleanMethod(env, containerCls, isArrM);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); isArr = JNI_FALSE; }
    (*env)->DeleteLocalRef(env, classCls);
    if (isArr) {
        jmethodID getCompM = (*env)->GetMethodID(env, (*env)->FindClass(env, "java/lang/Class"), "getComponentType", "()Ljava/lang/Class;");
        jclass compType = (*env)->CallObjectMethod(env, containerCls, getCompM);
        if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); compType = NULL; }
        if (compType) {
            jmethodID isPrimM = (*env)->GetMethodID(env, (*env)->FindClass(env, "java/lang/Class"), "isPrimitive", "()Z");
            jboolean isPrim = (*env)->CallBooleanMethod(env, compType, isPrimM);
            if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); isPrim = JNI_TRUE; }
            (*env)->DeleteLocalRef(env, compType);
            if (!isPrim) {
                jint len = (*env)->GetArrayLength(env, (jarray)container);
                jclass objCls = (*env)->FindClass(env, "java/lang/Object");
                (*env)->EnsureLocalCapacity(env, len + 32);
                jobjectArray res = (*env)->NewObjectArray(env, len, objCls, NULL);
                for (int i = 0; i < len; i++) {
                    __try {
                        jobject e = (*env)->GetObjectArrayElement(env, (jobjectArray)container, i);
                        if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); continue; }
                        if (e) { (*env)->SetObjectArrayElement(env, res, i, e); (*env)->DeleteLocalRef(env, e); }
                    } __except(EXCEPTION_EXECUTE_HANDLER) {
                        (*env)->ExceptionClear(env);
                        continue;
                    }
                }
                (*env)->DeleteLocalRef(env, objCls);
                (*env)->DeleteLocalRef(env, containerCls);
                return res;
            }
        }
    }
    jclass objCls = (*env)->FindClass(env, "java/lang/Object");
    jclass cur = (jclass)(*env)->NewLocalRef(env, containerCls);
    jfieldID arrFid = NULL, sizeFid = NULL;
    while (cur && !(*env)->IsSameObject(env, cur, objCls)) {
        jint fc = 0; jfieldID* fids = NULL;
        if ((*gJvmti)->GetClassFields(gJvmti, cur, &fc, &fids) == JVMTI_ERROR_NONE) {
            for (int i = 0; i < fc; i++) {
                jint m = 0;
                (*gJvmti)->GetFieldModifiers(gJvmti, cur, fids[i], &m);
                if (m & 0x0008) continue;
                char* fn = NULL; char* fs = NULL;
                (*gJvmti)->GetFieldName(gJvmti, cur, fids[i], &fn, &fs, NULL);
                if (fs && strcmp(fs, "[Ljava/lang/Object;") == 0 && !arrFid) arrFid = fids[i];
                if (fs && strcmp(fs, "I") == 0 && fn && strcmp(fn, "size") == 0 && !sizeFid) sizeFid = fids[i];
                if (fn) (*gJvmti)->Deallocate(gJvmti, (unsigned char*)fn);
                if (fs) (*gJvmti)->Deallocate(gJvmti, (unsigned char*)fs);
            }
            (*gJvmti)->Deallocate(gJvmti, (unsigned char*)fids);
        }
        if (arrFid) break;
        jclass sup = (*env)->GetSuperclass(env, cur);
        (*env)->DeleteLocalRef(env, cur);
        cur = sup;
    }
    if (cur) (*env)->DeleteLocalRef(env, cur);
    if (arrFid) {
        jobject dataArr = NULL;
        __try {
            dataArr = (*env)->GetObjectField(env, container, arrFid);
            if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); dataArr = NULL; }
        } __except(EXCEPTION_EXECUTE_HANDLER) {
            (*env)->ExceptionClear(env);
            dataArr = NULL;
        }
        if (dataArr) {
            jint arrLen = (*env)->GetArrayLength(env, (jarray)dataArr);
            int size = arrLen;
            if (sizeFid) {
                size = (*env)->GetIntField(env, container, sizeFid);
                if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); size = arrLen; }
                if (size > arrLen || size < 0) size = arrLen;
            }
            (*env)->EnsureLocalCapacity(env, size + 32);
            jobjectArray res = (*env)->NewObjectArray(env, size, objCls, NULL);
            for (int i = 0; i < size; i++) {
                __try {
                    jobject e = (*env)->GetObjectArrayElement(env, (jobjectArray)dataArr, i);
                    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); continue; }
                    if (e) { (*env)->SetObjectArrayElement(env, res, i, e); (*env)->DeleteLocalRef(env, e); }
                } __except(EXCEPTION_EXECUTE_HANDLER) {
                    (*env)->ExceptionClear(env);
                    continue;
                }
            }
            (*env)->DeleteLocalRef(env, dataArr);
            (*env)->DeleteLocalRef(env, objCls);
            (*env)->DeleteLocalRef(env, containerCls);
            return res;
        }
    }
    (*env)->DeleteLocalRef(env, objCls);
    (*env)->DeleteLocalRef(env, containerCls);
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_jp_mikumiku_lal_enforcement_ObjectLinker_nativeRemoveFromCollection(
    JNIEnv* env, jclass cls, jobject collection, jobject element)
{
    if (!gJvmti || !collection || !element) return JNI_FALSE;
    (*env)->EnsureLocalCapacity(env, 256);
    jclass objCls = (*env)->FindClass(env, "java/lang/Object");
    if (!objCls) { (*env)->ExceptionClear(env); return JNI_FALSE; }
    jclass cur = (*env)->GetObjectClass(env, collection);
    jfieldID arrFid = NULL, sizeFid = NULL, modCountFid = NULL;
    while (cur && !(*env)->IsSameObject(env, cur, objCls)) {
        jint fc = 0; jfieldID* fids = NULL;
        if ((*gJvmti)->GetClassFields(gJvmti, cur, &fc, &fids) == JVMTI_ERROR_NONE) {
            for (int i = 0; i < fc; i++) {
                jint m = 0;
                (*gJvmti)->GetFieldModifiers(gJvmti, cur, fids[i], &m);
                if (m & 0x0008) continue;
                char* fn = NULL; char* fs = NULL;
                (*gJvmti)->GetFieldName(gJvmti, cur, fids[i], &fn, &fs, NULL);
                if (fs && strcmp(fs, "[Ljava/lang/Object;") == 0 && !arrFid) arrFid = fids[i];
                if (fs && strcmp(fs, "I") == 0 && fn) {
                    if (strcmp(fn, "size") == 0 && !sizeFid) sizeFid = fids[i];
                    if (strcmp(fn, "modCount") == 0 && !modCountFid) modCountFid = fids[i];
                }
                if (fn) (*gJvmti)->Deallocate(gJvmti, (unsigned char*)fn);
                if (fs) (*gJvmti)->Deallocate(gJvmti, (unsigned char*)fs);
            }
            (*gJvmti)->Deallocate(gJvmti, (unsigned char*)fids);
        }
        if (arrFid && sizeFid) break;
        jclass sup = (*env)->GetSuperclass(env, cur);
        (*env)->DeleteLocalRef(env, cur);
        cur = sup;
    }
    if (cur) (*env)->DeleteLocalRef(env, cur);
    if (!arrFid) { (*env)->DeleteLocalRef(env, objCls); return JNI_FALSE; }
    jobject dataArr = NULL;
    __try {
        dataArr = (*env)->GetObjectField(env, collection, arrFid);
        if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); dataArr = NULL; }
    } __except(EXCEPTION_EXECUTE_HANDLER) {
        (*env)->ExceptionClear(env);
        dataArr = NULL;
    }
    if (!dataArr) { (*env)->DeleteLocalRef(env, objCls); return JNI_FALSE; }
    jint arrLen = (*env)->GetArrayLength(env, (jarray)dataArr);
    int size = arrLen;
    if (sizeFid) {
        size = (*env)->GetIntField(env, collection, sizeFid);
        if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); size = arrLen; }
        if (size > arrLen || size < 0) size = arrLen;
    }
    jboolean removed = JNI_FALSE;
    for (int i = 0; i < size; i++) {
        __try {
            jobject e = (*env)->GetObjectArrayElement(env, (jobjectArray)dataArr, i);
            if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); continue; }
            jboolean same = (e != NULL) ? (*env)->IsSameObject(env, e, element) : JNI_FALSE;
            if (e) (*env)->DeleteLocalRef(env, e);
            if (same) {
                for (int j = i; j < size - 1; j++) {
                    jobject next = (*env)->GetObjectArrayElement(env, (jobjectArray)dataArr, j + 1);
                    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); next = NULL; }
                    (*env)->SetObjectArrayElement(env, (jobjectArray)dataArr, j, next);
                    if (next) (*env)->DeleteLocalRef(env, next);
                }
                (*env)->SetObjectArrayElement(env, (jobjectArray)dataArr, size - 1, NULL);
                if (sizeFid) {
                    (*env)->SetIntField(env, collection, sizeFid, size - 1);
                    (*env)->ExceptionClear(env);
                }
                if (modCountFid) {
                    jint mc = (*env)->GetIntField(env, collection, modCountFid);
                    (*env)->ExceptionClear(env);
                    (*env)->SetIntField(env, collection, modCountFid, mc + 1);
                    (*env)->ExceptionClear(env);
                }
                removed = JNI_TRUE;
                break;
            }
        } __except(EXCEPTION_EXECUTE_HANDLER) {
            (*env)->ExceptionClear(env);
            continue;
        }
    }
    (*env)->DeleteLocalRef(env, dataArr);
    (*env)->DeleteLocalRef(env, objCls);
    return removed;
}

JNIEXPORT void JNICALL
Java_jp_mikumiku_lal_enforcement_ObjectKillEnforcer_nativeNeutralize(
    JNIEnv* env, jclass cls, jobject target)
{
    if (!gJvmti || !target) return;
    (*env)->EnsureLocalCapacity(env, 256);
    jclass objCls = (*env)->FindClass(env, "java/lang/Object");
    if (!objCls) { (*env)->ExceptionClear(env); return; }
    jclass cur = (*env)->GetObjectClass(env, target);
    while (cur && !(*env)->IsSameObject(env, cur, objCls)) {
        jint fc = 0; jfieldID* fids = NULL;
        if ((*gJvmti)->GetClassFields(gJvmti, cur, &fc, &fids) == JVMTI_ERROR_NONE) {
            for (int i = 0; i < fc; i++) {
                jint m = 0;
                (*gJvmti)->GetFieldModifiers(gJvmti, cur, fids[i], &m);
                if (m & 0x0008) continue;
                char* fn = NULL; char* fs = NULL;
                (*gJvmti)->GetFieldName(gJvmti, cur, fids[i], &fn, &fs, NULL);
                if (!fn || !fs) {
                    if (fn) (*gJvmti)->Deallocate(gJvmti, (unsigned char*)fn);
                    if (fs) (*gJvmti)->Deallocate(gJvmti, (unsigned char*)fs);
                    continue;
                }
                char low[256];
                jni_toLower(low, fn, 256);
                if (strcmp(fs, "F") == 0) {
                    (*env)->SetFloatField(env, target, fids[i], 0.0f);
                    (*env)->ExceptionClear(env);
                } else if (strcmp(fs, "D") == 0 && tmsHintMatch(low, TMS_HEALTH_HINTS, N_HEALTH)) {
                    (*env)->SetDoubleField(env, target, fids[i], 0.0);
                    (*env)->ExceptionClear(env);
                } else if (strcmp(fs, "Z") == 0) {
                    if (tmsHintMatch(low, TMS_ALIVE_HINTS, N_ALIVE)) {
                        (*env)->SetBooleanField(env, target, fids[i], JNI_FALSE);
                        (*env)->ExceptionClear(env);
                    } else if (tmsHintMatch(low, TMS_DEAD_HINTS, N_DEAD)) {
                        (*env)->SetBooleanField(env, target, fids[i], JNI_TRUE);
                        (*env)->ExceptionClear(env);
                    }
                } else if (strcmp(fs, "I") == 0 && tmsHintMatch(low, TMS_HEALTH_HINTS, N_HEALTH)) {
                    (*env)->SetIntField(env, target, fids[i], 0);
                    (*env)->ExceptionClear(env);
                }
                (*gJvmti)->Deallocate(gJvmti, (unsigned char*)fn);
                (*gJvmti)->Deallocate(gJvmti, (unsigned char*)fs);
            }
            (*gJvmti)->Deallocate(gJvmti, (unsigned char*)fids);
        }
        jclass sup = (*env)->GetSuperclass(env, cur);
        (*env)->DeleteLocalRef(env, cur);
        cur = sup;
    }
    if (cur) (*env)->DeleteLocalRef(env, cur);
    (*env)->DeleteLocalRef(env, objCls);
}

JNIEXPORT void JNICALL
Java_jp_mikumiku_lal_entity_LALEntityManager_nativeRegister(JNIEnv* env, jclass cls, jlong hi, jlong lo) {
    EnterCriticalSection(&gLALEntityLock);
    for (int i = 0; i < LAL_ENTITY_CAP; i++) {
        if (gLALEntities[i].occupied && gLALEntities[i].uuid_hi == hi && gLALEntities[i].uuid_lo == lo) {
            LeaveCriticalSection(&gLALEntityLock);
            return;
        }
    }
    for (int i = 0; i < LAL_ENTITY_CAP; i++) {
        if (!gLALEntities[i].occupied) {
            gLALEntities[i].uuid_hi = hi;
            gLALEntities[i].uuid_lo = lo;
            gLALEntities[i].posX = 0.0;
            gLALEntities[i].posY = 0.0;
            gLALEntities[i].posZ = 0.0;
            gLALEntities[i].rotX = 0.0f;
            gLALEntities[i].rotY = 0.0f;
            gLALEntities[i].tickCount = 0;
            gLALEntities[i].occupied = 1;
            break;
        }
    }
    LeaveCriticalSection(&gLALEntityLock);
}

JNIEXPORT void JNICALL
Java_jp_mikumiku_lal_entity_LALEntityManager_nativeUnregister(JNIEnv* env, jclass cls, jlong hi, jlong lo) {
    EnterCriticalSection(&gLALEntityLock);
    for (int i = 0; i < LAL_ENTITY_CAP; i++) {
        if (gLALEntities[i].occupied && gLALEntities[i].uuid_hi == hi && gLALEntities[i].uuid_lo == lo) {
            gLALEntities[i].occupied = 0;
            break;
        }
    }
    LeaveCriticalSection(&gLALEntityLock);
}

JNIEXPORT void JNICALL
Java_jp_mikumiku_lal_entity_LALEntityManager_nativeUpdatePos(
    JNIEnv* env, jclass cls, jlong hi, jlong lo,
    jdouble x, jdouble y, jdouble z, jfloat rx, jfloat ry)
{
    EnterCriticalSection(&gLALEntityLock);
    for (int i = 0; i < LAL_ENTITY_CAP; i++) {
        if (gLALEntities[i].occupied && gLALEntities[i].uuid_hi == hi && gLALEntities[i].uuid_lo == lo) {
            gLALEntities[i].posX = x;
            gLALEntities[i].posY = y;
            gLALEntities[i].posZ = z;
            gLALEntities[i].rotX = rx;
            gLALEntities[i].rotY = ry;
            gLALEntities[i].tickCount++;
            break;
        }
    }
    LeaveCriticalSection(&gLALEntityLock);
}

JNIEXPORT jint JNICALL
Java_jp_mikumiku_lal_entity_LALEntityManager_nativeGetCount(JNIEnv* env, jclass cls) {
    EnterCriticalSection(&gLALEntityLock);
    int count = 0;
    for (int i = 0; i < LAL_ENTITY_CAP; i++) {
        if (gLALEntities[i].occupied) count++;
    }
    LeaveCriticalSection(&gLALEntityLock);
    return count;
}

JNIEXPORT jdoubleArray JNICALL
Java_jp_mikumiku_lal_entity_LALEntityManager_nativeGetEntry(JNIEnv* env, jclass cls, jint index) {
    EnterCriticalSection(&gLALEntityLock);
    int found = 0;
    int count = 0;
    NativeLALEntity entry;
    memset(&entry, 0, sizeof(entry));
    for (int i = 0; i < LAL_ENTITY_CAP; i++) {
        if (gLALEntities[i].occupied) {
            if (count == index) {
                entry = gLALEntities[i];
                found = 1;
                break;
            }
            count++;
        }
    }
    LeaveCriticalSection(&gLALEntityLock);
    if (!found) return NULL;
    jdoubleArray result = (*env)->NewDoubleArray(env, 7);
    if (!result) return NULL;
    double data[7];
    data[0] = (double)entry.uuid_hi;
    data[1] = (double)entry.uuid_lo;
    data[2] = entry.posX;
    data[3] = entry.posY;
    data[4] = entry.posZ;
    data[5] = (double)entry.rotX;
    data[6] = (double)entry.rotY;
    (*env)->SetDoubleArrayRegion(env, result, 0, 7, data);
    return result;
}

JNIEXPORT jint JNICALL
Java_jp_mikumiku_lal_entity_LALEntityManager_nativeVerifyAndRestore(JNIEnv* env, jclass cls, jint javaCount) {
    EnterCriticalSection(&gLALEntityLock);
    int nativeCount = 0;
    for (int i = 0; i < LAL_ENTITY_CAP; i++) {
        if (gLALEntities[i].occupied) nativeCount++;
    }
    LeaveCriticalSection(&gLALEntityLock);
    if (nativeCount > javaCount) return nativeCount;
    return 0;
}

static jobject gFoundInstrumentation = NULL;
static jint JNICALL heapObjCb(jlong class_tag, jlong size, jlong* tag_ptr, void* user_data) {
    *tag_ptr = 1;
    return JVMTI_VISIT_OBJECTS;
}

JNIEXPORT jobject JNICALL
Java_jp_mikumiku_lal_agent_LALAgentBridge_nativeGetInstrumentation(JNIEnv* env, jclass cls) {
    if (!gJvmti || !gJvm) return NULL;
    jclass instImplClass = (*env)->FindClass(env, "sun/instrument/InstrumentationImpl");
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return NULL; }
    if (!instImplClass) return NULL;

    jint count = 0;
    jobject* instances = NULL;

    jvmtiError err = (*gJvmti)->GetLoadedClasses(gJvmti, &count, NULL);
    if (err != JVMTI_ERROR_NONE) {
        jvmtiHeapCallbacks hcb;
        memset(&hcb, 0, sizeof(hcb));
        hcb.heap_iteration_callback = heapObjCb;
        err = (*gJvmti)->IterateThroughHeap(gJvmti, JVMTI_HEAP_FILTER_CLASS_UNTAGGED, instImplClass, &hcb, NULL);
        if (err == JVMTI_ERROR_NONE) {
            jlong tag = 1;
            jint found = 0;
            err = (*gJvmti)->GetObjectsWithTags(gJvmti, 1, &tag, &found, &instances, NULL);
            if (err == JVMTI_ERROR_NONE && found > 0 && instances) {
                jobject result = (*env)->NewLocalRef(env, instances[0]);
                for (int i = 0; i < found; i++) {
                    (*gJvmti)->SetTag(gJvmti, instances[i], 0);
                }
                (*gJvmti)->Deallocate(gJvmti, (unsigned char*)instances);
                return result;
            }
            if (instances) (*gJvmti)->Deallocate(gJvmti, (unsigned char*)instances);
        }
        return NULL;
    }

    jclass* classes = NULL;
    err = (*gJvmti)->GetLoadedClasses(gJvmti, &count, &classes);
    if (err != JVMTI_ERROR_NONE || !classes) return NULL;

    for (int i = 0; i < count; i++) {
        if ((*env)->IsAssignableFrom(env, classes[i], instImplClass)) {
            jfieldID* fields = NULL;
            jint fcount = 0;
            if ((*gJvmti)->GetClassFields(gJvmti, classes[i], &fcount, &fields) == JVMTI_ERROR_NONE) {
                for (int j = 0; j < fcount; j++) {
                    jint mods = 0;
                    (*gJvmti)->GetFieldModifiers(gJvmti, classes[i], fields[j], &mods);
                    if (!(mods & 0x0008)) continue;
                    char* sig = NULL;
                    (*gJvmti)->GetFieldName(gJvmti, classes[i], fields[j], NULL, &sig, NULL);
                    if (sig && sig[0] == 'L') {
                        jobject val = (*env)->GetStaticObjectField(env, classes[i], fields[j]);
                        if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); }
                        else if (val && (*env)->IsInstanceOf(env, val, instImplClass)) {
                            if (sig) (*gJvmti)->Deallocate(gJvmti, (unsigned char*)sig);
                            (*gJvmti)->Deallocate(gJvmti, (unsigned char*)fields);
                            (*gJvmti)->Deallocate(gJvmti, (unsigned char*)classes);
                            return val;
                        }
                    }
                    if (sig) (*gJvmti)->Deallocate(gJvmti, (unsigned char*)sig);
                }
                (*gJvmti)->Deallocate(gJvmti, (unsigned char*)fields);
            }
        }
    }
    (*gJvmti)->Deallocate(gJvmti, (unsigned char*)classes);
    return NULL;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    gRunning = FALSE;
    if (gBypassKey != TLS_OUT_OF_INDEXES) TlsFree(gBypassKey);
    gJvmti = NULL;
    gJvm = NULL;
}
