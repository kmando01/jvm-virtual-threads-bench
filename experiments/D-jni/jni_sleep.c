#include <jni.h>
#include <unistd.h>

/*
 * 실험 D: JNI pinning 증명용 native 함수.
 * 이 함수는 JNI native frame 안에서 실행된다.
 * 가상 스레드가 이 함수를 호출하면 JVM은 carrier 스레드를 함께 블로킹(pin)한다.
 * JDK 21/25 모두 동일하게 pinning 발생 — JEP 491 은 native frame을 커버하지 않는다.
 */
JNIEXPORT void JNICALL Java_JniSleepProbe_nativeSleep(
        JNIEnv *env, jclass cls, jlong millis) {
    usleep((useconds_t)(millis * 1000L));
}
