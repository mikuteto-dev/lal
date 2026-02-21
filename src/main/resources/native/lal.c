#include <jvmti.h>
#include <jni.h>
#include <windows.h>
#include <string.h>

static jvmtiEnv* gJvmti = NULL;
static JavaVM* gJvm = NULL;
static volatile BOOL gRunning = TRUE;
static volatile BOOL gRetransformNeeded = FALSE;

static const char* LAL_TARGETS[] = {
    "net/minecraft/world/entity/Entity",
    "net/minecraft/world/entity/LivingEntity",
    "net/minecraft/world/entity/player/Player",
    "net/minecraft/server/level/ServerPlayer",
    "net/minecraft/server/level/ServerLevel",
    NULL
};

static BOOL isTarget(const char* name) {
    if (!name) return FALSE;
    for (int i = 0; LAL_TARGETS[i]; i++) {
        if (strcmp(name, LAL_TARGETS[i]) == 0) return TRUE;
    }
    return FALSE;
}

static void JNICALL ClassFileLoadHookCB(
    jvmtiEnv* jvmti_env,
    JNIEnv* jni_env,
    jclass class_being_redefined,
    jobject loader,
    const char* name,
    jobject protection_domain,
    jint class_data_len,
    const unsigned char* class_data,
    jint* new_class_data_len,
    unsigned char** new_class_data)
{
    if (new_class_data) *new_class_data = NULL;
    if (new_class_data_len) *new_class_data_len = 0;
    if (!class_being_redefined || !name) return;
    if (isTarget(name)) {
        gRetransformNeeded = TRUE;
    }
}

static DWORD WINAPI RetransformThread(LPVOID param) {
    if (!gJvm || !gJvmti) return 0;

    Sleep(5000);

    JNIEnv* env = NULL;
    if ((*gJvm)->AttachCurrentThreadAsDaemon(gJvm, (void**)&env, NULL) != JNI_OK || !env) return 0;

    jclass agentClass = (*env)->FindClass(env, "jp/mikumiku/lal/agent/LALAgent");
    if (!agentClass) { (*env)->ExceptionClear(env); (*gJvm)->DetachCurrentThread(gJvm); return 0; }

    jmethodID retransformMethod = (*env)->GetStaticMethodID(env, agentClass, "retransformTargetClasses", "()V");
    if (!retransformMethod) { (*env)->ExceptionClear(env); (*gJvm)->DetachCurrentThread(gJvm); return 0; }

    while (gRunning) {
        Sleep(500);
        if (!gRetransformNeeded) continue;
        gRetransformNeeded = FALSE;
        (*env)->CallStaticVoidMethod(env, agentClass, retransformMethod);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
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

    jvmtiCapabilities caps;
    memset(&caps, 0, sizeof(caps));
    caps.can_retransform_classes = 1;
    caps.can_generate_all_class_hook_events = 1;
    if ((*jvmti)->AddCapabilities(jvmti, &caps) != JVMTI_ERROR_NONE) return JNI_VERSION_1_8;

    jvmtiEventCallbacks callbacks;
    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.ClassFileLoadHook = ClassFileLoadHookCB;
    if ((*jvmti)->SetEventCallbacks(jvmti, &callbacks, sizeof(callbacks)) != JVMTI_ERROR_NONE) return JNI_VERSION_1_8;

    if ((*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, NULL) != JVMTI_ERROR_NONE) return JNI_VERSION_1_8;

    HANDLE thread = CreateThread(NULL, 0, RetransformThread, NULL, 0, NULL);
    if (thread) CloseHandle(thread);

    return JNI_VERSION_1_8;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    gRunning = FALSE;
    gJvmti = NULL;
    gJvm = NULL;
}
