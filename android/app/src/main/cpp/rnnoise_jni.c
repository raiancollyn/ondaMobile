#include <jni.h>
#include <stdint.h>
#include <stddef.h>
#include "rnnoise.h"

#define ONDA_RNNOISE_FRAME 480

JNIEXPORT jlong JNICALL
Java_net_dpbr_onda_RnnoiseProcessor_nativeCreate(JNIEnv *env, jobject instance) {
    DenoiseState *state = rnnoise_create(NULL);
    return (jlong)(intptr_t)state;
}

JNIEXPORT jboolean JNICALL
Java_net_dpbr_onda_RnnoiseProcessor_nativeProcess(
    JNIEnv *env,
    jobject instance,
    jlong handle,
    jobject buffer,
    jint frames
) {
    DenoiseState *state = (DenoiseState *)(intptr_t)handle;
    if (state == NULL || buffer == NULL || frames != ONDA_RNNOISE_FRAME) {
        return JNI_FALSE;
    }

    float *samples = (float *)(*env)->GetDirectBufferAddress(env, buffer);
    if (samples == NULL) {
        return JNI_FALSE;
    }

    rnnoise_process_frame(state, samples, samples);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_net_dpbr_onda_RnnoiseProcessor_nativeDestroy(JNIEnv *env, jobject instance, jlong handle) {
    DenoiseState *state = (DenoiseState *)(intptr_t)handle;
    if (state != NULL) {
        rnnoise_destroy(state);
    }
}
