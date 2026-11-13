/*
 * The JNI bridge between the local agent's Kotlin engine and QuickJS-NG.
 *
 * Why this file exists at all: `app.cash.quickjs:quickjs-android:0.9.2` (the
 * only published QuickJS binding, 2021) evaluates scripts but never runs the
 * promise job queue — `JS_ExecutePendingJob` is simply not in its embedder loop.
 * The Mizar kernel is promise-based from its first line (`createKernel` is an
 * async function that awaits the file system before it can boot), so on that
 * binding the kernel parses, starts booting, and then freezes forever. This
 * bridge is the smallest embedder loop that does the job properly:
 *
 *   evaluate -> drain JS_ExecutePendingJob until empty -> hand the result back
 *
 * It also solves the second problem the AAR had: the published `.so` files are
 * 4 KB-aligned ELF, so they cannot load on a 16 KB page-size device. NDK r28
 * builds with `-Wl,-z,max-page-size=16384` (see Android.mk) and the app targets
 * that from the start.
 *
 * The Java-facing surface is deliberately tiny (four methods) and the JS-facing
 * surface is one function, `__mizarNativeCall(op, a, b)`:
 *
 *   - Kotlin -> JS: `evaluate(handle, script, fileName)`.
 *   - JS -> Kotlin: `__mizarNativeCall` forwards the three strings to
 *     `HostDispatcher.call(op, a, b)` and returns its string result to JS.
 *
 * Everything richer (the platform host API, protocol frames, timers) is
 * JavaScript in `assets/localagent-host.js`; this layer only has to be correct,
 * not clever.
 *
 * Threading: the engine is single-threaded by construction — Kotlin only ever
 * calls in from the one HandlerThread that owns it, so every call here arrives
 * on a thread already attached to the JVM and no locking is needed. The JS
 * callback runs synchronously inside `evaluate`, on that same thread.
 */

#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <android/log.h>

#include "quickjs.h"

#define LOG_TAG "LocalAgentJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/** One booted engine: runtime, context, and the Java dispatcher it calls back. */
typedef struct MizarEngine {
    JSRuntime *runtime;
    JSContext *context;
    jobject dispatcher;     /* global reference, owned by this handle */
    jmethodID dispatch;     /* HostDispatcher.call(String, String, String) */
} MizarEngine;

static JavaVM *g_vm = NULL;

/* ------------------------------------------------------------------------ */
/* UTF-16 <-> UTF-8                                                          */
/*                                                                          */
/* JNI's NewStringUTF/GetStringUTFChars speak "modified UTF-8" (CESU-8 for   */
/* astral characters), while QuickJS speaks UTF-8. Using the two together is */
/* fine for ASCII and BMP text and silently wrong for emoji, so both         */
/* directions convert explicitly through UTF-16.                            */
/* ------------------------------------------------------------------------ */

static size_t utf16_to_utf8(const jchar *chars, jsize length, char *out)
{
    size_t written = 0;
    for (jsize index = 0; index < length; index += 1) {
        uint32_t code = chars[index];
        if (code >= 0xD800 && code <= 0xDBFF && index + 1 < length) {
            uint32_t low = chars[index + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                code = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00);
                index += 1;
            }
        }
        if (code < 0x80) {
            out[written++] = (char) code;
        } else if (code < 0x800) {
            out[written++] = (char) (0xC0 | (code >> 6));
            out[written++] = (char) (0x80 | (code & 0x3F));
        } else if (code < 0x10000) {
            out[written++] = (char) (0xE0 | (code >> 12));
            out[written++] = (char) (0x80 | ((code >> 6) & 0x3F));
            out[written++] = (char) (0x80 | (code & 0x3F));
        } else {
            out[written++] = (char) (0xF0 | (code >> 18));
            out[written++] = (char) (0x80 | ((code >> 12) & 0x3F));
            out[written++] = (char) (0x80 | ((code >> 6) & 0x3F));
            out[written++] = (char) (0x80 | (code & 0x3F));
        }
    }
    return written;
}

/** Copy a Java string into a NUL-terminated UTF-8 buffer; caller frees. */
static char *java_string_to_utf8(JNIEnv *env, jstring value, size_t *out_length)
{
    if (value == NULL) {
        if (out_length != NULL) *out_length = 0;
        return NULL;
    }
    jsize length = (*env)->GetStringLength(env, value);
    const jchar *chars = (*env)->GetStringChars(env, value, NULL);
    if (chars == NULL) {
        if (out_length != NULL) *out_length = 0;
        return NULL;
    }
    /* Worst case is three bytes per UTF-16 unit (a surrogate pair, two units,
     * is four bytes), plus the terminator. */
    char *buffer = (char *) malloc((size_t) length * 3 + 4);
    if (buffer == NULL) {
        (*env)->ReleaseStringChars(env, value, chars);
        if (out_length != NULL) *out_length = 0;
        return NULL;
    }
    size_t written = utf16_to_utf8(chars, length, buffer);
    buffer[written] = '\0';
    (*env)->ReleaseStringChars(env, value, chars);
    if (out_length != NULL) *out_length = written;
    return buffer;
}

/** Build a Java string from UTF-8 bytes, replacing malformed sequences. */
static jstring utf8_to_java_string(JNIEnv *env, const char *utf8, size_t length)
{
    if (utf8 == NULL) return NULL;
    /* One UTF-16 unit per byte is the worst case: a four-byte sequence becomes
     * a surrogate pair, which is exactly two units. */
    jchar *units = (jchar *) malloc(length * sizeof(jchar) + sizeof(jchar));
    if (units == NULL) return NULL;

    size_t written = 0;
    size_t index = 0;
    while (index < length) {
        uint32_t code = 0xFFFD;
        unsigned char lead = (unsigned char) utf8[index];
        int extra = 0;
        if (lead < 0x80) {
            code = lead;
        } else if ((lead & 0xE0) == 0xC0) {
            code = lead & 0x1F;
            extra = 1;
        } else if ((lead & 0xF0) == 0xE0) {
            code = lead & 0x0F;
            extra = 2;
        } else if ((lead & 0xF8) == 0xF0) {
            code = lead & 0x07;
            extra = 3;
        } else {
            index += 1;
            units[written++] = 0xFFFD;
            continue;
        }
        if (index + (size_t) extra >= length) {
            index = length;
            units[written++] = 0xFFFD;
            continue;
        }
        bool valid = true;
        for (int step = 1; step <= extra; step += 1) {
            unsigned char continuation = (unsigned char) utf8[index + step];
            if ((continuation & 0xC0) != 0x80) {
                valid = false;
                break;
            }
            code = (code << 6) | (continuation & 0x3F);
        }
        index += (size_t) extra + 1;
        if (!valid) {
            units[written++] = 0xFFFD;
            continue;
        }
        if (code >= 0x10000) {
            uint32_t astral = code - 0x10000;
            units[written++] = (jchar) (0xD800 + (astral >> 10));
            units[written++] = (jchar) (0xDC00 + (astral & 0x3FF));
        } else {
            units[written++] = (jchar) code;
        }
    }
    jstring result = (*env)->NewString(env, units, (jsize) written);
    free(units);
    return result;
}

/** A JS value as a Java string; objects fall back to their JS string form. */
static jstring js_value_to_java_string(JNIEnv *env, JSContext *context, JSValueConst value)
{
    if (JS_IsUndefined(value) || JS_IsNull(value)) return NULL;
    size_t length = 0;
    const char *utf8 = JS_ToCStringLen(context, &length, value);
    if (utf8 == NULL) return NULL;
    jstring result = utf8_to_java_string(env, utf8, length);
    JS_FreeCString(context, utf8);
    return result;
}

/** A Java string as a JS value; a null Java string becomes JS null. */
static JSValue java_string_to_js_value(JNIEnv *env, JSContext *context, jstring value)
{
    if (value == NULL) return JS_NULL;
    size_t length = 0;
    char *utf8 = java_string_to_utf8(env, value, &length);
    if (utf8 == NULL) return JS_NULL;
    JSValue result = JS_NewStringLen(context, utf8, length);
    free(utf8);
    return result;
}

/* ------------------------------------------------------------------------ */
/* The one JS -> Kotlin entry point                                          */
/* ------------------------------------------------------------------------ */

/**
 * `__mizarNativeCall(op, first, second)` — forwards to
 * `HostDispatcher.call(op, first, second)` and returns the answer as a JS
 * string. The Kotlin side answers with a JSON envelope; the JavaScript host
 * shim (`assets/localagent-host.js`) is the only consumer.
 *
 * A Java exception is reported as a JS error rather than a crash: the kernel's
 * host API has no failure channel for a bridge that dies, and an error the
 * kernel can surface is worth more than a tombstone.
 */
static JSValue js_native_call(JSContext *context, JSValueConst this_val, int argc, JSValueConst *argv)
{
    (void) this_val;
    MizarEngine *engine = (MizarEngine *) JS_GetContextOpaque(context);
    if (engine == NULL || engine->dispatcher == NULL) {
        return JS_ThrowInternalError(context, "the host dispatcher is not attached");
    }
    JNIEnv *env = NULL;
    if ((*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK || env == NULL) {
        return JS_ThrowInternalError(context, "the JS thread is not attached to the JVM");
    }

    jstring arguments[3] = {NULL, NULL, NULL};
    for (int index = 0; index < 3; index += 1) {
        if (index < argc) {
            size_t length = 0;
            const char *text = JS_ToCStringLen(context, &length, argv[index]);
            if (text != NULL) {
                arguments[index] = utf8_to_java_string(env, text, length);
                JS_FreeCString(context, text);
            }
        }
        if (arguments[index] == NULL) {
            arguments[index] = (*env)->NewStringUTF(env, "");
        }
    }

    jstring result = (jstring) (*env)->CallObjectMethod(
        env, engine->dispatcher, engine->dispatch, arguments[0], arguments[1], arguments[2]);
    for (int index = 0; index < 3; index += 1) {
        if (arguments[index] != NULL) (*env)->DeleteLocalRef(env, arguments[index]);
    }
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return JS_ThrowInternalError(context, "the host dispatcher threw");
    }
    JSValue value = java_string_to_js_value(env, context, result);
    if (result != NULL) (*env)->DeleteLocalRef(env, result);
    return value;
}

/* ------------------------------------------------------------------------ */
/* The JNI surface                                                           */
/* ------------------------------------------------------------------------ */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void) reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_me_shirazu_dshmizar_localagent_QuickJsNative_create(JNIEnv *env, jobject thiz)
{
    (void) env;
    (void) thiz;
    MizarEngine *engine = (MizarEngine *) calloc(1, sizeof(MizarEngine));
    if (engine == NULL) return 0;
    engine->runtime = JS_NewRuntime();
    if (engine->runtime == NULL) {
        free(engine);
        return 0;
    }
    JS_SetRuntimeInfo(engine->runtime, "mizar-android-quickjs-ng");
    /* A bound, not a requirement: the kernel keeps sessions and config as text;
     * 256 MB is far above what the PoC needs and low enough that a runaway
     * model turn surfaces as a JS error instead of an OOM kill. */
    JS_SetMemoryLimit(engine->runtime, 256 * 1024 * 1024);
    engine->context = JS_NewContext(engine->runtime);
    if (engine->context == NULL) {
        JS_FreeRuntime(engine->runtime);
        free(engine);
        return 0;
    }
    JS_SetContextOpaque(engine->context, engine);

    JSValue global = JS_GetGlobalObject(engine->context);
    if (JS_IsException(global)) {
        JS_FreeContext(engine->context);
        JS_FreeRuntime(engine->runtime);
        free(engine);
        return 0;
    }
    JS_SetPropertyStr(engine->context, global, "__mizarNativeCall",
                      JS_NewCFunction(engine->context, js_native_call, "__mizarNativeCall", 3));
    JS_FreeValue(engine->context, global);
    LOGI("quickjs-ng context created");
    return (jlong) (intptr_t) engine;
}

JNIEXPORT void JNICALL
Java_me_shirazu_dshmizar_localagent_QuickJsNative_setDispatcher(
    JNIEnv *env, jobject thiz, jlong handle, jobject dispatcher)
{
    (void) thiz;
    MizarEngine *engine = (MizarEngine *) (intptr_t) handle;
    if (engine == NULL) return;
    if (engine->dispatcher != NULL) {
        (*env)->DeleteGlobalRef(env, engine->dispatcher);
        engine->dispatcher = NULL;
        engine->dispatch = NULL;
    }
    if (dispatcher == NULL) return;
    engine->dispatcher = (*env)->NewGlobalRef(env, dispatcher);
    jclass dispatcher_class = (*env)->GetObjectClass(env, dispatcher);
    engine->dispatch = (*env)->GetMethodID(
        env, dispatcher_class, "call",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    (*env)->DeleteLocalRef(env, dispatcher_class);
    if (engine->dispatch == NULL) {
        LOGE("HostDispatcher.call was not found; the host bridge is unusable");
    }
}

JNIEXPORT jstring JNICALL
Java_me_shirazu_dshmizar_localagent_QuickJsNative_evaluate(
    JNIEnv *env, jobject thiz, jlong handle, jstring source, jstring file_name)
{
    (void) thiz;
    MizarEngine *engine = (MizarEngine *) (intptr_t) handle;
    if (engine == NULL) {
        jclass error_class = (*env)->FindClass(env, "java/lang/IllegalStateException");
        (*env)->ThrowNew(env, error_class, "the JS engine handle is closed");
        return NULL;
    }

    size_t source_length = 0;
    char *source_utf8 = java_string_to_utf8(env, source, &source_length);
    size_t file_length = 0;
    char *file_utf8 = java_string_to_utf8(env, file_name, &file_length);
    if (source_utf8 == NULL) {
        free(file_utf8);
        return NULL;
    }
    (void) file_length;

    JSValue value = JS_Eval(engine->context,
                            source_utf8, source_length,
                            file_utf8 != NULL ? file_utf8 : "<eval>",
                            JS_EVAL_TYPE_GLOBAL);
    free(source_utf8);
    free(file_utf8);

    if (JS_IsException(value)) {
        JSValue exception = JS_GetException(engine->context);
        jstring message = js_value_to_java_string(env, engine->context, exception);
        JS_FreeValue(engine->context, exception);
        JS_FreeValue(engine->context, value);

        char detail[2048] = "JavaScript evaluation failed";
        if (message != NULL) {
            const char *text = (*env)->GetStringUTFChars(env, message, NULL);
            if (text != NULL) {
                snprintf(detail, sizeof(detail), "JavaScript evaluation failed: %s", text);
                (*env)->ReleaseStringUTFChars(env, message, text);
            }
            (*env)->DeleteLocalRef(env, message);
        }
        LOGE("%s", detail);
        jclass error_class = (*env)->FindClass(env, "java/lang/RuntimeException");
        if (error_class != NULL) {
            (*env)->ThrowNew(env, error_class, detail);
            (*env)->DeleteLocalRef(env, error_class);
        }
        return NULL;
    }

    /*
     * The loop the published binding was missing. Every `await` in the kernel
     * resumes here; the queue is empty only when nothing is left to run, which
     * is exactly when an `evaluate` can hand a result back.
     */
    for (;;) {
        JSContext *job_context = NULL;
        int status = JS_ExecutePendingJob(engine->runtime, &job_context);
        if (status == 0) break;
        if (status < 0) {
            JSValue exception = JS_GetException(job_context);
            size_t length = 0;
            const char *text = JS_ToCStringLen(job_context, &length, exception);
            LOGE("a promise job threw: %s", text != NULL ? text : "?");
            JS_FreeCString(job_context, text);
            JS_FreeValue(job_context, exception);
            /* Keep draining: one rejected promise must not strand the rest of
             * the queue. A rejection the kernel did not catch is a bug, but a
             * stuck engine is worse. */
        }
    }

    jstring result = js_value_to_java_string(env, engine->context, value);
    JS_FreeValue(engine->context, value);
    return result;
}

JNIEXPORT void JNICALL
Java_me_shirazu_dshmizar_localagent_QuickJsNative_close(JNIEnv *env, jobject thiz, jlong handle)
{
    (void) thiz;
    MizarEngine *engine = (MizarEngine *) (intptr_t) handle;
    if (engine == NULL) return;
    if (engine->dispatcher != NULL) {
        (*env)->DeleteGlobalRef(env, engine->dispatcher);
        engine->dispatcher = NULL;
    }
    if (engine->context != NULL) {
        JS_FreeContext(engine->context);
        engine->context = NULL;
    }
    if (engine->runtime != NULL) {
        JS_FreeRuntime(engine->runtime);
        engine->runtime = NULL;
    }
    free(engine);
    LOGI("quickjs-ng context destroyed");
}
