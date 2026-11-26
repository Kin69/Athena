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

#include "../athena.h"

void clear(struct context *ctx) {
    if (ctx == NULL) {
        return;
    }

    struct ng_session *s = ctx->ng_session;
    while (s != NULL) {
        struct ng_session *next = s->next;  // Save next pointer before freeing

        if (s->socket >= 0) {
            if (close(s->socket) != 0) {
                log_android(ANDROID_LOG_WARN, "Failed to close socket %d during cleanup: %s", s->socket, strerror(errno));
            }
            s->socket = -1;
        }

        if (s->protocol == IPPROTO_TCP)
            clear_tcp_data(&s->tcp);

        ng_free(s, __FILE__, __LINE__);
        s = next;
    }

    // Clear the session list pointer at the END to prevent handle_events race conditions
    ctx->ng_session = NULL;
}

void *handle_events(void *a) {
    struct arguments *args = (struct arguments *) a;

    int maxsessions = SESSION_MAX;
    struct rlimit rlim;
    if (!getrlimit(RLIMIT_NOFILE, &rlim)) {
        maxsessions = (int) (rlim.rlim_cur * SESSION_LIMIT / 100);
        if (maxsessions > SESSION_MAX)
            maxsessions = SESSION_MAX;
    }

    int epoll_fd = epoll_create(1);
    if (epoll_fd < 0) {
        args->ctx->stopping = 1;
    }

    struct epoll_event ev_pipe;
    memset(&ev_pipe, 0, sizeof(struct epoll_event));
    ev_pipe.events = EPOLLIN | EPOLLERR;
    ev_pipe.data.ptr = &ev_pipe;
    if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, args->ctx->pipefds[0], &ev_pipe)) {
        args->ctx->stopping = 1;
    }

    struct epoll_event ev_tun;
    memset(&ev_tun, 0, sizeof(struct epoll_event));
    ev_tun.events = EPOLLIN | EPOLLERR;
    ev_tun.data.ptr = NULL;
    if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, args->tun, &ev_tun)) {
        args->ctx->stopping = 1;
    }

    log_android(ANDROID_LOG_DEBUG, "Event loop started on tun %d, max sessions %d", args->tun, maxsessions);

    long long last_check = 0;
    while (!args->ctx->stopping) {
        int recheck = 0;
        int timeout = EPOLL_TIMEOUT;

        int isessions = 0;
        int usessions = 0;
        int tsessions = 0;
        struct ng_session *s = args->ctx->ng_session;
        while (s != NULL) {
            if (s->protocol == IPPROTO_ICMP || s->protocol == IPPROTO_ICMPV6) {
                if (!s->icmp.stop)
                    isessions++;
            } else if (s->protocol == IPPROTO_UDP) {
                if (s->udp.state == UDP_ACTIVE)
                    usessions++;
            } else if (s->protocol == IPPROTO_TCP) {
                if (s->tcp.state != TCP_CLOSING && s->tcp.state != TCP_CLOSE)
                    tsessions++;
                if (s->socket >= 0)
                    recheck = recheck | monitor_tcp_session(args, s, epoll_fd);
            }
            s = s->next;
        }
        int sessions = isessions + usessions + tsessions;

        long long ms = get_ms();
        if (ms - last_check > EPOLL_MIN_CHECK) {
            last_check = ms;

            // Lock mutex before accessing/modifying session list to prevent race with clear()
            if (pthread_mutex_lock(&args->ctx->lock)) {
                log_android(ANDROID_LOG_ERROR, "Failed to lock mutex for session cleanup");
            } else {
                time_t now = time(NULL);
                struct ng_session *sl = NULL;
                s = args->ctx->ng_session;
                while (s != NULL) {
                    int del = 0;
                    if (s->protocol == IPPROTO_ICMP || s->protocol == IPPROTO_ICMPV6) {
                        del = check_icmp_session(args, s, sessions, maxsessions);
                        if (!s->icmp.stop && !del) {
                            int stimeout = s->icmp.time + get_icmp_timeout(&s->icmp, sessions, maxsessions) - now + 1;
                            if (stimeout > 0 && stimeout < timeout)
                                timeout = stimeout;
                        }
                    } else if (s->protocol == IPPROTO_UDP) {
                        del = check_udp_session(args, s, sessions, maxsessions);
                        if (s->udp.state == UDP_ACTIVE && !del) {
                            int stimeout = s->udp.time + get_udp_timeout(&s->udp, sessions, maxsessions) - now + 1;
                            if (stimeout > 0 && stimeout < timeout)
                                timeout = stimeout;
                        }
                    } else if (s->protocol == IPPROTO_TCP) {
                        del = check_tcp_session(args, s, sessions, maxsessions);
                        if (s->tcp.state != TCP_CLOSING && s->tcp.state != TCP_CLOSE && !del) {
                            int stimeout = s->tcp.time + get_tcp_timeout(&s->tcp, sessions, maxsessions) - now + 1;
                            if (stimeout > 0 && stimeout < timeout)
                                timeout = stimeout;
                        }
                    }

                    if (del) {
                        if (sl == NULL)
                            args->ctx->ng_session = s->next;
                        else
                            sl->next = s->next;

                        struct ng_session *c = s;
                        s = s->next;
                        if (c->protocol == IPPROTO_TCP)
                            clear_tcp_data(&c->tcp);
                        ng_free(c, __FILE__, __LINE__);
                    } else {
                        sl = s;
                        s = s->next;
                    }
                }

                pthread_mutex_unlock(&args->ctx->lock);
            }
        } else {
            recheck = 1;
        }

        struct epoll_event ev[EPOLL_EVENTS];
        int ready = epoll_wait(epoll_fd, ev, EPOLL_EVENTS, recheck ? EPOLL_MIN_CHECK : timeout * 1000);

        if (ready < 0) {
            if (errno == EINTR)
                continue;
            else
                break;
        }

        if (ready > 0) {
            if (pthread_mutex_lock(&args->ctx->lock))
                break;

            int error = 0;

            for (int i = 0; i < ready; i++) {
                if (ev[i].data.ptr == &ev_pipe) {
                    uint8_t buffer[1];
                    read(args->ctx->pipefds[0], buffer, 1);
                } else if (ev[i].data.ptr == NULL) {
                    int count = 0;
                    while (count < TUN_YIELD && !error && !args->ctx->stopping && is_readable(args->tun)) {
                        count++;
                        if (check_tun(args, &ev[i], epoll_fd, sessions, maxsessions) < 0)
                            error = 1;
                    }
                } else {
                    struct ng_session *session = (struct ng_session *) ev[i].data.ptr;
                    if (session->protocol == IPPROTO_ICMP || session->protocol == IPPROTO_ICMPV6)
                        check_icmp_socket(args, &ev[i]);
                    else if (session->protocol == IPPROTO_UDP) {
                        int count = 0;
                        while (count < UDP_YIELD && !args->ctx->stopping && !(ev[i].events & EPOLLERR) && (ev[i].events & EPOLLIN) && is_readable(session->socket)) {
                            count++;
                            check_udp_socket(args, &ev[i]);
                        }
                    } else if (session->protocol == IPPROTO_TCP)
                        check_tcp_socket(args, &ev[i], epoll_fd);
                }

                if (error)
                    break;
            }

            if (pthread_mutex_unlock(&args->ctx->lock))
                break;

            if (error)
                break;
        }
    }

    // Not stopping means an error ended the loop, for example the tun was closed under it
    log_android(ANDROID_LOG_DEBUG, "Event loop on tun %d ended, stopping %d errno %d", args->tun, args->ctx->stopping, errno);

    if (epoll_fd >= 0)
        close(epoll_fd);

    ng_free(args, __FILE__, __LINE__);

    return NULL;
}