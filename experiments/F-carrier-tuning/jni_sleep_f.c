#include <jni.h>
#include <unistd.h>

static void do_sleep(jlong millis) {
    usleep((useconds_t)(millis * 1000L));
}

JNIEXPORT void JNICALL Java_CarrierTuningProbe_nativeSleep(JNIEnv *e, jclass c, jlong ms) { do_sleep(ms); }
JNIEXPORT void JNICALL Java_PlatformJni_nativeSleep       (JNIEnv *e, jclass c, jlong ms) { do_sleep(ms); }
JNIEXPORT void JNICALL Java_MiniTest_nativeSleep           (JNIEnv *e, jclass c, jlong ms) { do_sleep(ms); }
