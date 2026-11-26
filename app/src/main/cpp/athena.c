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
    if (pthread_mutex_init(&ctx->lock, NULL)) log_android(ANDROID_LOG_ERROR, "pthread_mutex_init failed");
    if (pipe(ctx->pipefds)) log_android(ANDROID_LOG_ERROR, "Create pipe error %d: %s", errno, strerror(errno));
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
handle_events(args);
current_tun_fd = -1;  // The tunnel is closed after this, its fd number can be reused by any other file
}

JNIEXPORT void JNICALL Java_com_kin_athena_service_vpn_service_TunnelManager_jni_1stop(JNIEnv *env, jobject instance, jlong context) {
struct context *ctx = (struct context *) context;
ctx->stopping = 1;
write(ctx->pipefds[1], "w", 1);
}

JNIEXPORT void JNICALL Java_com_kin_athena_service_vpn_service_TunnelManager_jni_1done(JNIEnv *env, jobject instance, jlong context) {
    if (context == 0) return;

    struct context *ctx = (struct context *) context;
    if (ctx == NULL) return;

    // Lock before clearing to prevent race with handle_events
    if (pthread_mutex_lock(&ctx->lock) == 0) {
        clear(ctx);
        pthread_mutex_unlock(&ctx->lock);
    } else {
        // If lock fails, still try to clear (last resort cleanup)
        clear(ctx);
    }

    // Only destroy mutex if it was initialized
    if (pthread_mutex_destroy(&ctx->lock) != 0) {
        // Mutex was already destroyed or not initialized, continue cleanup
    }

    if (ctx->pipefds[0] >= 0) close(ctx->pipefds[0]);
    if (ctx->pipefds[1] >= 0) close(ctx->pipefds[1]);
    ng_free(ctx, __FILE__, __LINE__);
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

JNIEXPORT void JNICALL Java_com_kin_athena_service_vpn_service_TunnelManager_jni_1clear_1sessions(JNIEnv *env, jobject instance, jlong context) {
    if (context == 0) return;

    struct context *ctx = (struct context *) context;
    if (ctx == NULL) return;

    // Lock the context to prevent race conditions during session clearing
    long long lock_start = get_ms();
    if (pthread_mutex_lock(&ctx->lock) != 0) {
        log_android(ANDROID_LOG_ERROR, "Failed to lock context for session clearing");
        return;
    }
    long long locked_at = get_ms();

    // Tell the apps their connections are gone before freeing them. Without an RST an idle connection
    // (push, chat) still looks alive to the app and hangs until the app itself sends something, which for
    // Play services' push connection is its next heartbeat, often 10+ minutes after a network change.
    // Sessions still in LISTEN are left alone: the app retransmits the SYN and gets a new session.
    int reset = 0;
    if (current_tun_fd >= 0) {
        struct arguments rst_args = {.env = env, .instance = instance, .tun = current_tun_fd, .ctx = ctx};
        for (struct ng_session *s = ctx->ng_session; s != NULL; s = s->next)
            if (s->protocol == IPPROTO_TCP && s->tcp.state != TCP_LISTEN &&
                s->tcp.state != TCP_CLOSING && s->tcp.state != TCP_CLOSE) {
                write_rst(&rst_args, &s->tcp);
                reset++;
            }
    }
    log_android(ANDROID_LOG_INFO, "Clearing all active sessions, reset %d TCP connections", reset);

    int sessions = 0;
    for (struct ng_session *s = ctx->ng_session; s != NULL; s = s->next)
        sessions++;

    // Clear all sessions
    clear(ctx);

    // The event thread handles packets under this lock, so a long hold stalls all traffic
    log_android(ANDROID_LOG_INFO, "All sessions cleared successfully, freed %d sessions, waited %lld ms for the lock and held it %lld ms",
                sessions, locked_at - lock_start, get_ms() - locked_at);

    // Unlock the context
    if (pthread_mutex_unlock(&ctx->lock) != 0) {
        log_android(ANDROID_LOG_ERROR, "Failed to unlock context after session clearing");
    }
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

jboolean filter_tcp_packet(const struct arguments *args, const uint8_t *data, size_t length, const char *direction) {
    if (args == NULL || args->env == NULL || args->instance == NULL || data == NULL) {
        return JNI_TRUE; // Allow packet if arguments are invalid
    }
    
    JNIEnv *env = args->env;
    
    jclass cls = (*env)->GetObjectClass(env, args->instance);
    if (cls == NULL) return JNI_TRUE;
    
    jmethodID methodID = (*env)->GetMethodID(env, cls, "onTcpPacketReceived", "([BILjava/lang/String;)Z");
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