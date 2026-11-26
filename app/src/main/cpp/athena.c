/*
    This file is part of NetGuard.

    NetGuard is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    NetGuard is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2015-2024 by Marcel Bokhorst (M66B)
*/

#include "athena.h"

int loglevel = ANDROID_LOG_WARN;
static int current_tun_fd = -1;  // Global TUN file descriptor



jclass clsPacket;
jclass clsRR;

static void free_context(struct context *ctx);

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return -1;

    struct rlimit rlim;

    if (!getrlimit(RLIMIT_NOFILE, &rlim)) {
        rlim.rlim_cur = rlim.rlim_max;
        setrlimit(RLIMIT_NOFILE, &rlim);
    }

    return JNI_VERSION_1_6;
}

void JNI_OnUnload(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) == JNI_OK) {
        (*env)->DeleteGlobalRef(env, clsPacket);
        (*env)->DeleteGlobalRef(env, clsRR);
    }
}

JNIEXPORT jlong JNICALL Java_com_kin_athena_service_vpn_service_TunnelManager_jni_1init(JNIEnv *env, jobject instance, jint sdk) {
    struct context *ctx = ng_calloc(1, sizeof(struct context), "init");
    ctx->sdk = sdk;
    ctx->refs = 1; // the owner, until jni_done
    if (pthread_mutex_init(&ctx->lock, NULL)) log_android(ANDROID_LOG_ERROR, "pthread_mutex_init failed");
    if (pthread_mutex_init(&ctx->reset_lock, NULL)) log_android(ANDROID_LOG_ERROR, "pthread_mutex_init reset_lock failed");
    if (pipe(ctx->pipefds)) log_android(ANDROID_LOG_ERROR, "Create pipe error %d: %s", errno, strerror(errno));
    // Wake-ups must never block the caller: a full pipe already holds a pending wake-up, and the event thread drains it
    for (int i = 0; i < 2; i++) {
        int flags = fcntl(ctx->pipefds[i], F_GETFL, 0);
        if (flags < 0 || fcntl(ctx->pipefds[i], F_SETFL, flags | O_NONBLOCK) < 0)
            log_android(ANDROID_LOG_ERROR, "fcntl pipefds[%d] O_NONBLOCK error %d: %s", i, errno, strerror(errno));
    }
    return (jlong) ctx;
}

JNIEXPORT void JNICALL Java_com_kin_athena_service_vpn_service_TunnelManager_jni_1start(JNIEnv *env, jobject instance, jlong context, jint loglevel_) {
struct context *ctx = (struct context *) context;
loglevel = loglevel_;
ctx->stopping = 0;
}

JNIEXPORT void JNICALL Java_com_kin_athena_service_vpn_service_TunnelManager_jni_1run(JNIEnv *env, jobject instance, jlong context, jint tun, jboolean fwd53, jint rcode) {
struct context *ctx = (struct context *) context;
current_tun_fd = tun;  // Store TUN file descriptor globally
struct arguments *args = ng_malloc(sizeof(struct arguments), "arguments");
args->env = env;
args->instance = instance;
args->tun = tun;
args->fwd53 = fwd53;
args->rcode = rcode;
args->ctx = ctx;
// The event thread holds a reference, so jni_done cannot free the context while the loop still uses it
__atomic_add_fetch(&ctx->refs, 1, __ATOMIC_ACQ_REL);
handle_events(args);
current_tun_fd = -1;  // The tunnel is closed after this, its fd number can be reused by any other file
if (__atomic_sub_fetch(&ctx->refs, 1, __ATOMIC_ACQ_REL) == 0) {
    log_android(ANDROID_LOG_INFO, "Event loop ended after jni_done, freeing the context now");
    free_context(ctx);
}
}

JNIEXPORT void JNICALL Java_com_kin_athena_service_vpn_service_TunnelManager_jni_1stop(JNIEnv *env, jobject instance, jlong context) {
struct context *ctx = (struct context *) context;
ctx->stopping = 1;
write(ctx->pipefds[1], "w", 1);
}

// Frees the context once nothing uses it anymore: no event loop runs and the owner called jni_done
static void free_context(struct context *ctx) {
    clear(ctx);
    pthread_mutex_destroy(&ctx->lock);
    // A reset request that the event thread did not take anymore
    if (ctx->reset_uids != NULL)
        ng_free(ctx->reset_uids, __FILE__, __LINE__);
    pthread_mutex_destroy(&ctx->reset_lock);
    if (ctx->pipefds[0] >= 0) close(ctx->pipefds[0]);
    if (ctx->pipefds[1] >= 0) close(ctx->pipefds[1]);
    ng_free(ctx, __FILE__, __LINE__);
}

JNIEXPORT void JNICALL Java_com_kin_athena_service_vpn_service_TunnelManager_jni_1done(JNIEnv *env, jobject instance, jlong context) {
    if (context == 0) return;

    struct context *ctx = (struct context *) context;
    if (ctx == NULL) return;

    // The event loop may still be between its stop check and its next wake-up. Freeing the context (and destroying
    // its mutexes) under it crashed with "pthread_mutex_lock called on a destroyed mutex". Make sure it stops, and
    // let whoever drops the last reference free the context: here, or jni_run once the loop has ended.
    ctx->stopping = 1;
    write(ctx->pipefds[1], "d", 1);
    if (__atomic_sub_fetch(&ctx->refs, 1, __ATOMIC_ACQ_REL) == 0)
        free_context(ctx);
    else
        log_android(ANDROID_LOG_INFO, "jni_done while the event loop still runs, it frees the context when it ends");
}

JNIEXPORT jstring JNICALL Java_com_kin_athena_service_vpn_service_TunnelManager_jni_1getprop(JNIEnv *env, jclass type, jstring name_) {
    const char *name = (*env)->GetStringUTFChars(env, name_, 0);
    char value[PROP_VALUE_MAX + 1] = "";
    __system_property_get(name, value);
    (*env)->ReleaseStringUTFChars(env, name_, name);
    return (*env)->NewStringUTF(env, value);
}

JNIEXPORT jint JNICALL
Java_com_kin_athena_service_vpn_service_TunnelManager_jni_1get_1mtu(JNIEnv *env, jobject instance) {
    return get_mtu();
}

// Wakes the event thread, which then handles pending clears and resets at the top of its loop
static void wake_event_thread(struct context *ctx, const char *why) {
    if (write(ctx->pipefds[1], "c", 1) < 0 && errno != EAGAIN)
        log_android(ANDROID_LOG_ERROR, "%s wake-up error %d: %s", why, errno, strerror(errno));
}

JNIEXPORT void JNICALL Java_com_kin_athena_service_vpn_service_TunnelManager_jni_1clear_1sessions(JNIEnv *env, jobject instance, jlong context) {
    if (context == 0) return;

    struct context *ctx = (struct context *) context;
    if (ctx == NULL) return;

    // Only record the request and return at once: the event thread clears at the top of its loop (clear_sessions),
    // where it uses no session pointer. Freeing sessions here, on the caller's thread, would race with the event
    // thread using them, and waiting for its lock blocked the main thread for up to a second.
    // Several requests before the event thread runs are one clear.
    if (!__atomic_load_n(&ctx->clear_pending, __ATOMIC_ACQUIRE))
        __atomic_store_n(&ctx->clear_requested, get_ms(), __ATOMIC_RELEASE);
    __atomic_store_n(&ctx->clear_pending, 1, __ATOMIC_RELEASE);
    log_android(ANDROID_LOG_INFO, "Clear of all sessions requested, the event thread clears them");
    wake_event_thread(ctx, "Clear sessions");
}

JNIEXPORT void JNICALL Java_com_kin_athena_service_vpn_service_TunnelManager_jni_1reset_1sessions(JNIEnv *env, jobject instance, jlong context, jintArray uids_) {
    if (context == 0 || uids_ == NULL) return;

    struct context *ctx = (struct context *) context;
    if (ctx == NULL) return;

    jsize count = (*env)->GetArrayLength(env, uids_);
    if (count <= 0) return;
    jint *uids = (*env)->GetIntArrayElements(env, uids_, NULL);
    if (uids == NULL) return;

    // Like jni_clear_sessions, only record the uids: the event thread resets their TCP sessions (reset_uid_sessions).
    // A request that is still pending is merged with this one.
    if (pthread_mutex_lock(&ctx->reset_lock) == 0) {
        if (ctx->reset_uids == NULL) {
            ctx->reset_count = 0;
            ctx->reset_requested = get_ms();
        }
        // Without duplicates and bounded, in case the event thread is gone and nothing takes the request
        jint *merged = (ctx->reset_count + count <= RESET_UIDS_MAX ?
                        ng_malloc((ctx->reset_count + count) * sizeof(jint), "reset uids") : NULL);
        if (merged == NULL) {
            pthread_mutex_unlock(&ctx->reset_lock);
            log_android(ANDROID_LOG_ERROR, "Reset of %d uids dropped: %d already pending", (int) count, ctx->reset_count);
            (*env)->ReleaseIntArrayElements(env, uids_, uids, JNI_ABORT);
            return;
        }
        int merged_count = 0;
        if (ctx->reset_uids != NULL) {
            memcpy(merged, ctx->reset_uids, ctx->reset_count * sizeof(jint));
            merged_count = ctx->reset_count;
            ng_free(ctx->reset_uids, __FILE__, __LINE__);
        }
        for (jsize i = 0; i < count; i++) {
            int known = 0;
            for (int j = 0; j < merged_count && !known; j++)
                known = (merged[j] == uids[i]);
            if (!known)
                merged[merged_count++] = uids[i];
        }
        ctx->reset_uids = merged;
        ctx->reset_count = merged_count;
        pthread_mutex_unlock(&ctx->reset_lock);
        log_android(ANDROID_LOG_INFO, "Reset of the TCP sessions of %d uids requested, the event thread resets them", (int) count);
        wake_event_thread(ctx, "Reset sessions");
    } else {
        log_android(ANDROID_LOG_ERROR, "Failed to lock the reset request");
    }

    (*env)->ReleaseIntArrayElements(env, uids_, uids, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_com_kin_athena_service_vpn_service_TunnelManager_jni_1reset_1sessions_1before(JNIEnv *env, jobject instance, jlong context, jlong before) {
    if (context == 0 || before <= 0) return;

    struct context *ctx = (struct context *) context;
    if (ctx == NULL) return;

    // Like jni_clear_sessions: the event thread resets the TCP sessions created before this time
    // (reset_sessions_before). Several pending requests merge into the latest time.
    long long current = __atomic_load_n(&ctx->reset_before, __ATOMIC_ACQUIRE);
    if (current == 0)
        __atomic_store_n(&ctx->reset_before_requested, get_ms(), __ATOMIC_RELEASE);
    while (before > current &&
           !__atomic_compare_exchange_n(&ctx->reset_before, &current, (long long) before, 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE));
    log_android(ANDROID_LOG_INFO, "Reset of the TCP sessions created more than %lld ms ago requested, the event thread resets them",
                get_ms() - (long long) before);
    wake_event_thread(ctx, "Reset sessions before");
}

JNIEXPORT void JNICALL Java_com_kin_athena_service_vpn_service_TunnelManager_jni_1set_1dns_1servers(JNIEnv *env, jobject instance, jlong context, jstring dnsV4, jstring dnsV6) {
    if (context == 0) return;

    struct context *ctx = (struct context *) context;
    if (ctx == NULL) return;

    // Get UTF strings from Java
    const char *dns_v4_str = (*env)->GetStringUTFChars(env, dnsV4, 0);
    const char *dns_v6_str = (*env)->GetStringUTFChars(env, dnsV6, 0);

    if (dns_v4_str != NULL) {
        strncpy(ctx->dns_server_v4, dns_v4_str, INET_ADDRSTRLEN - 1);
        ctx->dns_server_v4[INET_ADDRSTRLEN - 1] = '\0';
        log_android(ANDROID_LOG_INFO, "DNS IPv4 server set to: %s", ctx->dns_server_v4);
        (*env)->ReleaseStringUTFChars(env, dnsV4, dns_v4_str);
    }

    if (dns_v6_str != NULL) {
        strncpy(ctx->dns_server_v6, dns_v6_str, INET6_ADDRSTRLEN - 1);
        ctx->dns_server_v6[INET6_ADDRSTRLEN - 1] = '\0';
        log_android(ANDROID_LOG_INFO, "DNS IPv6 server set to: %s", ctx->dns_server_v6);
        (*env)->ReleaseStringUTFChars(env, dnsV6, dns_v6_str);
    }
}

JNIEXPORT void JNICALL Java_com_kin_athena_service_vpn_service_TunnelManager_jni_1send_1complete_1packet(JNIEnv *env, jobject instance, jlong context, jbyteArray packetData) {
    if (context == 0) return;

    struct context *ctx = (struct context *) context;
    if (ctx == NULL) return;

    // Get complete packet data from Java byte array
    jbyte *packet_bytes = (*env)->GetByteArrayElements(env, packetData, NULL);
    jsize packet_length = (*env)->GetArrayLength(env, packetData);
    
    if (packet_bytes == NULL || packet_length <= 0) {
        log_android(ANDROID_LOG_ERROR, "Complete packet: Invalid packet data");
        return;
    }

    // Log the complete packet details
    log_android(ANDROID_LOG_DEBUG, "Complete packet details:");
    log_android(ANDROID_LOG_DEBUG, "  Packet size: %zd bytes", (size_t) packet_length);
    log_android(ANDROID_LOG_DEBUG, "  TUN fd: %d", current_tun_fd);
    
    // Log first 64 bytes of complete packet in hex
    char hex_buffer[512];
    int hex_len = 0;
    int bytes_to_log = packet_length < 64 ? packet_length : 64;
    for (int i = 0; i < bytes_to_log; i++) {
        hex_len += snprintf(hex_buffer + hex_len, sizeof(hex_buffer) - hex_len, 
                           "%02x ", (unsigned char)packet_bytes[i]);
    }
    log_android(ANDROID_LOG_DEBUG, "  Packet data (first %d bytes): %s", bytes_to_log, hex_buffer);

    // Write complete packet directly to TUN interface
    ssize_t written = write(current_tun_fd, packet_bytes, packet_length);
    
    if (written > 0) {
        log_android(ANDROID_LOG_DEBUG, "Complete packet sent successfully: %zd bytes written to TUN interface", written);
    } else {
        log_android(ANDROID_LOG_ERROR, "Failed to send complete packet: %zd (TUN fd: %d, errno: %d)", written, current_tun_fd, errno);
    }

    // Release Java byte array
    (*env)->ReleaseByteArrayElements(env, packetData, packet_bytes, JNI_ABORT);
}

void ng_delete_alloc(void *ptr, const char *file, int line) {
#ifdef PROFILE_MEMORY
    if (ptr == NULL)
        return;

    if (pthread_mutex_lock(alock))
        log_android(ANDROID_LOG_ERROR, "pthread_mutex_lock failed");

    int found = 0;
    for (int c = 0; c < allocs; c++)
        if (alloc[c].ptr == ptr) {
            found = 1;
            alloc[c].tag = "[free]";
            alloc[c].ptr = NULL;
            break;
        }

    if (found == 1)
        balance--;

    log_android(found ? ANDROID_LOG_DEBUG : ANDROID_LOG_ERROR,
                "alloc/free balance %d records %d found %d", balance, allocs, found);
    if (found == 0)
        log_android(ANDROID_LOG_ERROR, "Not found at %s:%d", file, line);

    if (pthread_mutex_unlock(alock))
        log_android(ANDROID_LOG_ERROR, "pthread_mutex_unlock failed");
#endif
}

void log_packet_hex(const struct arguments *args, const uint8_t *data, size_t length, const char *direction) {
    if (args == NULL || args->env == NULL || args->instance == NULL || data == NULL) {
        return;
    }
    
    JNIEnv *env = args->env;
    
    jclass cls = (*env)->GetObjectClass(env, args->instance);
    if (cls == NULL) return;
    
    jmethodID methodID = (*env)->GetMethodID(env, cls, "onPacketReceived", "([BILjava/lang/String;)V");
    if (methodID == NULL) {
        (*env)->DeleteLocalRef(env, cls);
        return;
    }
    
    jbyteArray dataArray = (*env)->NewByteArray(env, (jsize)length);
    if (dataArray == NULL) {
        (*env)->DeleteLocalRef(env, cls);
        return;
    }
    
    (*env)->SetByteArrayRegion(env, dataArray, 0, (jsize)length, (const jbyte*)data);
    
    jstring directionStr = (*env)->NewStringUTF(env, direction);
    if (directionStr == NULL) {
        (*env)->DeleteLocalRef(env, dataArray);
        (*env)->DeleteLocalRef(env, cls);
        return;
    }
    
    (*env)->CallVoidMethod(env, args->instance, methodID, dataArray, (jint)length, directionStr);
    
    (*env)->DeleteLocalRef(env, dataArray);
    (*env)->DeleteLocalRef(env, directionStr);
    (*env)->DeleteLocalRef(env, cls);
}

// Returns TCP_FILTER_DROP if the packet is dropped, otherwise the uid that owns the connection (-1 if unknown)
jint filter_tcp_packet(const struct arguments *args, const uint8_t *data, size_t length, const char *direction) {
    if (args == NULL || args->env == NULL || args->instance == NULL || data == NULL) {
        return -1; // Allow packet if arguments are invalid, owner unknown
    }
    
    JNIEnv *env = args->env;
    
    jclass cls = (*env)->GetObjectClass(env, args->instance);
    if (cls == NULL) return -1;
    
    jmethodID methodID = (*env)->GetMethodID(env, cls, "onTcpPacketReceived", "([BILjava/lang/String;)I");
    if (methodID == NULL) {
        (*env)->DeleteLocalRef(env, cls);
        return -1;
    }
    
    jbyteArray dataArray = (*env)->NewByteArray(env, (jsize)length);
    if (dataArray == NULL) {
        (*env)->DeleteLocalRef(env, cls);
        return -1;
    }
    
    (*env)->SetByteArrayRegion(env, dataArray, 0, (jsize)length, (const jbyte*)data);
    
    jstring directionStr = (*env)->NewStringUTF(env, direction);
    if (directionStr == NULL) {
        (*env)->DeleteLocalRef(env, dataArray);
        (*env)->DeleteLocalRef(env, cls);
        return -1;
    }
    
    jint result = (*env)->CallIntMethod(env, args->instance, methodID, dataArray, (jint)length, directionStr);
    // An escaped Throwable leaves the result undefined (0 would read as "allowed, uid 0"), and a pending exception
    // makes the next JNI calls illegal: drop the packet
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        log_android(ANDROID_LOG_ERROR, "TCP filter threw, dropping the packet");
        result = TCP_FILTER_DROP;
    }
    
    (*env)->DeleteLocalRef(env, dataArray);
    (*env)->DeleteLocalRef(env, directionStr);
    (*env)->DeleteLocalRef(env, cls);
    
    return result;
}

jboolean filter_udp_packet(const struct arguments *args, const uint8_t *data, size_t length, const char *direction) {
    if (args == NULL || args->env == NULL || args->instance == NULL || data == NULL) {
        return JNI_TRUE; // Allow packet if arguments are invalid
    }
    
    JNIEnv *env = args->env;
    
    jclass cls = (*env)->GetObjectClass(env, args->instance);
    if (cls == NULL) return JNI_TRUE;
    
    jmethodID methodID = (*env)->GetMethodID(env, cls, "onUdpPacketReceived", "([BILjava/lang/String;)Z");
    if (methodID == NULL) {
        (*env)->DeleteLocalRef(env, cls);
        return JNI_TRUE;
    }
    
    jbyteArray dataArray = (*env)->NewByteArray(env, (jsize)length);
    if (dataArray == NULL) {
        (*env)->DeleteLocalRef(env, cls);
        return JNI_TRUE;
    }
    
    (*env)->SetByteArrayRegion(env, dataArray, 0, (jsize)length, (const jbyte*)data);
    
    jstring directionStr = (*env)->NewStringUTF(env, direction);
    if (directionStr == NULL) {
        (*env)->DeleteLocalRef(env, dataArray);
        (*env)->DeleteLocalRef(env, cls);
        return JNI_TRUE;
    }
    
    jboolean result = (*env)->CallBooleanMethod(env, args->instance, methodID, dataArray, (jint)length, directionStr);
    
    (*env)->DeleteLocalRef(env, dataArray);
    (*env)->DeleteLocalRef(env, directionStr);
    (*env)->DeleteLocalRef(env, cls);
    
    return result;
}

// Tells TunnelManager.onUpstreamDns that a forwarded DNS query sent at sent (get_ms) got no answer, or that answers
// come again after unanswered queries. Event thread only.
void report_upstream_dns(const struct arguments *args, jboolean answered, long long sent) {
    if (args == NULL || args->env == NULL || args->instance == NULL) {
        return;
    }

    JNIEnv *env = args->env;

    jclass cls = (*env)->GetObjectClass(env, args->instance);
    if (cls == NULL) return;

    jmethodID methodID = (*env)->GetMethodID(env, cls, "onUpstreamDns", "(ZJ)V");
    if (methodID != NULL)
        (*env)->CallVoidMethod(env, args->instance, methodID, answered, (jlong) sent);
    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionClear(env);

    (*env)->DeleteLocalRef(env, cls);
}

// Tells TunnelManager.onResetBeforeDone that the RSTs for the TCP sessions created before before (get_ms) are written
// to the tun: reset sessions got one, at done (get_ms). Event thread only, outside ctx->lock.
void report_reset_before(const struct arguments *args, long long before, int reset, long long done) {
    if (args == NULL || args->env == NULL || args->instance == NULL) {
        return;
    }

    JNIEnv *env = args->env;

    jclass cls = (*env)->GetObjectClass(env, args->instance);
    if (cls == NULL) return;

    jmethodID methodID = (*env)->GetMethodID(env, cls, "onResetBeforeDone", "(JIJ)V");
    if (methodID != NULL)
        (*env)->CallVoidMethod(env, args->instance, methodID, (jlong) before, (jint) reset, (jlong) done);
    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionClear(env);

    (*env)->DeleteLocalRef(env, cls);
}

jboolean filter_icmp_packet(const struct arguments *args, const uint8_t *data, size_t length, const char *direction) {
    if (args == NULL || args->env == NULL || args->instance == NULL || data == NULL) {
        return JNI_TRUE; // Allow packet if arguments are invalid
    }
    
    JNIEnv *env = args->env;
    
    jclass cls = (*env)->GetObjectClass(env, args->instance);
    if (cls == NULL) return JNI_TRUE;
    
    jmethodID methodID = (*env)->GetMethodID(env, cls, "onIcmpPacketReceived", "([BILjava/lang/String;)Z");
    if (methodID == NULL) {
        (*env)->DeleteLocalRef(env, cls);
        return JNI_TRUE;
    }
    
    jbyteArray dataArray = (*env)->NewByteArray(env, (jsize)length);
    if (dataArray == NULL) {
        (*env)->DeleteLocalRef(env, cls);
        return JNI_TRUE;
    }
    
    (*env)->SetByteArrayRegion(env, dataArray, 0, (jsize)length, (const jbyte*)data);
    
    jstring directionStr = (*env)->NewStringUTF(env, direction);
    if (directionStr == NULL) {
        (*env)->DeleteLocalRef(env, dataArray);
        (*env)->DeleteLocalRef(env, cls);
        return JNI_TRUE;
    }
    
    jboolean result = (*env)->CallBooleanMethod(env, args->instance, methodID, dataArray, (jint)length, directionStr);
    
    (*env)->DeleteLocalRef(env, dataArray);
    (*env)->DeleteLocalRef(env, directionStr);
    (*env)->DeleteLocalRef(env, cls);
    
    return result;
}