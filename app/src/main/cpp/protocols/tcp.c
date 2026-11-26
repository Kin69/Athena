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

char socks5_addr[INET6_ADDRSTRLEN + 1];
int socks5_port = 0;
char socks5_username[127 + 1];
char socks5_password[127 + 1];

void clear_tcp_data(struct tcp_session *cur) {
    struct segment *s = cur->forward;
    while (s != NULL) {
        struct segment *p = s;
        s = s->next;
        ng_free(p->data, __FILE__, __LINE__);
        ng_free(p, __FILE__, __LINE__);
    }
}

int get_tcp_timeout(const struct tcp_session *t, int sessions, int maxsessions) {
    int timeout;
    if (t->state == TCP_LISTEN || t->state == TCP_SYN_RECV)
        timeout = TCP_INIT_TIMEOUT;
    else if (t->state == TCP_ESTABLISHED)
        timeout = TCP_IDLE_TIMEOUT;
    else
        timeout = TCP_CLOSE_TIMEOUT;

    int scale = 100 - sessions * 100 / maxsessions;
    timeout = timeout * scale / 100;

    return timeout;
}

int check_tcp_session(const struct arguments *args, struct ng_session *s, int sessions, int maxsessions) {
    time_t now = time(NULL);

    char source[INET6_ADDRSTRLEN + 1];
    char dest[INET6_ADDRSTRLEN + 1];
    if (s->tcp.version == 4) {
        inet_ntop(AF_INET, &s->tcp.saddr.ip4, source, sizeof(source));
        inet_ntop(AF_INET, &s->tcp.daddr.ip4, dest, sizeof(dest));
    } else {
        inet_ntop(AF_INET6, &s->tcp.saddr.ip6, source, sizeof(source));
        inet_ntop(AF_INET6, &s->tcp.daddr.ip6, dest, sizeof(dest));
    }

    int timeout = get_tcp_timeout(&s->tcp, sessions, maxsessions);

    if (s->tcp.state != TCP_CLOSING && s->tcp.state != TCP_CLOSE && s->tcp.time + timeout < now) {
        if (s->tcp.state == TCP_LISTEN)
            s->tcp.state = TCP_CLOSING;
        else
            write_rst(args, &s->tcp);
    }

    if (s->tcp.state == TCP_CLOSING) {
        if (s->socket >= 0) {
            if (close(s->socket) == 0) {
                s->socket = -1;
            } else {
                // Log close error but still mark as closed to prevent double close
                log_android(ANDROID_LOG_WARN, "Failed to close socket %d: %s", s->socket, strerror(errno));
                s->socket = -1;
            }
        }
        s->tcp.time = time(NULL);
        s->tcp.state = TCP_CLOSE;
    }

    if ((s->tcp.state == TCP_CLOSING || s->tcp.state == TCP_CLOSE) && (s->tcp.sent || s->tcp.received)) {
        s->tcp.sent = 0;
        s->tcp.received = 0;
    }

    if (s->tcp.state == TCP_CLOSE && s->tcp.time + TCP_KEEP_TIMEOUT < now)
        return 1;

    return 0;
}

int monitor_tcp_session(const struct arguments *args, struct ng_session *s, int epoll_fd) {
    int recheck = 0;
    unsigned int events = EPOLLERR;

    if (s->tcp.state == TCP_LISTEN) {
        if (s->tcp.socks5 == SOCKS5_NONE)
            events = events | EPOLLOUT;
        else
            events = events | EPOLLIN;
    } else if (s->tcp.state == TCP_ESTABLISHED || s->tcp.state == TCP_CLOSE_WAIT) {
        if (get_send_window(&s->tcp) > 0)
            events = events | EPOLLIN;
        else {
            recheck = 1;
            long long ms = get_ms();
            if (ms - s->tcp.last_keep_alive > EPOLL_MIN_CHECK) {
                s->tcp.last_keep_alive = ms;
                s->tcp.remote_seq--;
                write_ack(args, &s->tcp);
                s->tcp.remote_seq++;
            }
        }

        if (s->tcp.forward != NULL) {
            uint32_t buffer_size = get_receive_buffer(s);
            if (s->tcp.forward->seq == s->tcp.remote_seq && s->tcp.forward->len - s->tcp.forward->sent < buffer_size)
                events = events | EPOLLOUT;
            else
                recheck = 1;
        }
    }

    if (events != s->ev.events) {
        s->ev.events = events;
        if (epoll_ctl(epoll_fd, EPOLL_CTL_MOD, s->socket, &s->ev))
            s->tcp.state = TCP_CLOSING;
    }

    return recheck;
}

uint32_t get_send_window(const struct tcp_session *cur) {
    uint32_t behind;
    if (cur->acked <= cur->local_seq)
        behind = (cur->local_seq - cur->acked);
    else
        behind = (0x10000 + cur->local_seq - cur->acked);
    behind += (cur->unconfirmed + 1) * 40;

    uint32_t total = (behind < cur->send_window ? cur->send_window - behind : 0);

    return total;
}

void queue_tcp(const struct arguments *args, const struct tcphdr *tcphdr, const char *session, struct tcp_session *cur, const uint8_t *data, uint16_t datalen) {
    uint32_t seq = ntohl(tcphdr->seq);
    if (compare_u32(seq, cur->remote_seq) >= 0) {
        struct segment *p = NULL;
        struct segment *s = cur->forward;
        while (s != NULL && compare_u32(s->seq, seq) < 0) {
            p = s;
            s = s->next;
        }

        if (s == NULL || compare_u32(s->seq, seq) > 0) {
            struct segment *n = ng_malloc(sizeof(struct segment), "tcp segment");
            n->seq = seq;
            n->len = datalen;
            n->sent = 0;
            n->psh = tcphdr->psh;
            n->data = ng_malloc(datalen, "tcp segment");
            memcpy(n->data, data, datalen);
            n->next = s;
            if (p == NULL)
                cur->forward = n;
            else
                p->next = n;
        } else if (s != NULL && s->seq == seq) {
            if (s->len != datalen) {
                ng_free(s->data, __FILE__, __LINE__);
                s->len = datalen;
                s->data = ng_malloc(datalen, "tcp segment");
                memcpy(s->data, data, datalen);
            }
        }
    }
}

uint32_t get_receive_buffer(const struct ng_session *cur) {
    if (cur->socket < 0)
        return 0;

    int sendbuf = 0;
    int sendbufsize = sizeof(sendbuf);
    if (getsockopt(cur->socket, SOL_SOCKET, SO_SNDBUF, &sendbuf, (socklen_t *) &sendbufsize) < 0)
        return 0;

    if (sendbuf == 0)
        sendbuf = SEND_BUF_DEFAULT;

    int unsent = 0;
    if (ioctl(cur->socket, SIOCOUTQ, &unsent))
        return 0;

    uint32_t total = (uint32_t) (unsent < sendbuf ? sendbuf - unsent : 0);

    return total;
}

int write_data(const struct arguments *args, struct tcp_session *cur, const uint8_t *buffer, size_t length) {
    if (write_tcp(args, cur, buffer, length, 0, 1, 0, 0) < 0) {
        cur->state = TCP_CLOSING;
        return -1;
    }
    return 0;
}

int write_syn_ack(const struct arguments *args, struct tcp_session *cur) {
    if (write_tcp(args, cur, NULL, 0, 1, 1, 0, 0) < 0) {
        cur->state = TCP_CLOSING;
        return -1;
    }
    return 0;
}

int write_fin_ack(const struct arguments *args, struct tcp_session *cur) {
    if (write_tcp(args, cur, NULL, 0, 0, 1, 1, 0) < 0) {
        cur->state = TCP_CLOSING;
        return -1;
    }
    return 0;
}

uint32_t get_receive_window(const struct ng_session *cur) {
    uint32_t toforward = 0;
    struct segment *q = cur->tcp.forward;
    while (q != NULL) {
        toforward += (q->len - q->sent);
        q = q->next;
    }

    uint32_t window = get_receive_buffer(cur);

    uint32_t max = ((uint32_t) 0xFFFF) << cur->tcp.recv_scale;
    if (window > max)
        window = max;

    uint32_t total = (toforward < window ? window - toforward : 0);

    return total;
}

void check_tcp_socket(const struct arguments *args, const struct epoll_event *ev, const int epoll_fd) {
    struct ng_session *s = (struct ng_session *) ev->data.ptr;

    int oldstate = s->tcp.state;
    uint32_t oldlocal = s->tcp.local_seq;
    uint32_t oldremote = s->tcp.remote_seq;

    char source[INET6_ADDRSTRLEN + 1];
    char dest[INET6_ADDRSTRLEN + 1];
    if (s->tcp.version == 4) {
        inet_ntop(AF_INET, &s->tcp.saddr.ip4, source, sizeof(source));
        inet_ntop(AF_INET, &s->tcp.daddr.ip4, dest, sizeof(dest));
    } else {
        inet_ntop(AF_INET6, &s->tcp.saddr.ip6, source, sizeof(source));
        inet_ntop(AF_INET6, &s->tcp.daddr.ip6, dest, sizeof(dest));
    }

    if (ev->events & EPOLLERR) {
        s->tcp.time = time(NULL);
        int serr = 0;
        socklen_t optlen = sizeof(int);
        getsockopt(s->socket, SOL_SOCKET, SO_ERROR, &serr, &optlen);
        write_rst(args, &s->tcp);
    } else {
        if (s->tcp.state == TCP_LISTEN) {
            if (s->tcp.socks5 == SOCKS5_NONE) {
                if (ev->events & EPOLLOUT) {
                    if (*socks5_addr && socks5_port)
                        s->tcp.socks5 = SOCKS5_HELLO;
                    else
                        s->tcp.socks5 = SOCKS5_CONNECTED;
                }
            } else {
                if (ev->events & EPOLLIN) {
                    uint8_t buffer[32];
                    ssize_t bytes = recv(s->socket, buffer, sizeof(buffer), 0);
                    if (bytes < 0) {
                        write_rst(args, &s->tcp);
                    } else {
                        if (s->tcp.socks5 == SOCKS5_HELLO && bytes == 2 && buffer[0] == 5) {
                            if (buffer[1] == 0)
                                s->tcp.socks5 = SOCKS5_CONNECT;
                            else if (buffer[1] == 2)
                                s->tcp.socks5 = SOCKS5_AUTH;
                            else {
                                s->tcp.socks5 = 0;
                                write_rst(args, &s->tcp);
                            }
                        } else if (s->tcp.socks5 == SOCKS5_AUTH && bytes == 2 && (buffer[0] == 1 || buffer[0] == 5)) {
                            if (buffer[1] == 0)
                                s->tcp.socks5 = SOCKS5_CONNECT;
                            else {
                                s->tcp.socks5 = 0;
                                write_rst(args, &s->tcp);
                            }
                        } else if (s->tcp.socks5 == SOCKS5_CONNECT && bytes == 6 + (s->tcp.version == 4 ? 4 : 16) && buffer[0] == 5) {
                            if (buffer[1] == 0)
                                s->tcp.socks5 = SOCKS5_CONNECTED;
                            else {
                                s->tcp.socks5 = 0;
                                write_rst(args, &s->tcp);
                            }
                        } else {
                            s->tcp.socks5 = 0;
                            write_rst(args, &s->tcp);
                        }
                    }
                }
            }

            if (s->tcp.socks5 == SOCKS5_HELLO) {
                uint8_t buffer[4] = {5, 2, 0, 2};
                send(s->socket, buffer, sizeof(buffer), MSG_NOSIGNAL);
            } else if (s->tcp.socks5 == SOCKS5_AUTH) {
                uint8_t ulen = strlen(socks5_username);
                uint8_t plen = strlen(socks5_password);
                uint8_t buffer[512];
                *(buffer + 0) = 1;
                *(buffer + 1) = ulen;
                memcpy(buffer + 2, socks5_username, ulen);
                *(buffer + 2 + ulen) = plen;
                memcpy(buffer + 2 + ulen + 1, socks5_password, plen);
                size_t len = 2 + ulen + 1 + plen;
                send(s->socket, buffer, len, MSG_NOSIGNAL);
            } else if (s->tcp.socks5 == SOCKS5_CONNECT) {
                uint8_t buffer[22];
                *(buffer + 0) = 5;
                *(buffer + 1) = 1;
                *(buffer + 2) = 0;
                *(buffer + 3) = (uint8_t) (s->tcp.version == 4 ? 1 : 4);
                if (s->tcp.version == 4) {
                    memcpy(buffer + 4, &s->tcp.daddr.ip4, 4);
                    *((__be16 *) (buffer + 4 + 4)) = s->tcp.dest;
                } else {
                    memcpy(buffer + 4, &s->tcp.daddr.ip6, 16);
                    *((__be16 *) (buffer + 4 + 16)) = s->tcp.dest;
                }
                size_t len = (s->tcp.version == 4 ? 10 : 22);
                send(s->socket, buffer, len, MSG_NOSIGNAL);
            } else if (s->tcp.socks5 == SOCKS5_CONNECTED) {
                s->tcp.remote_seq++;
                if (write_syn_ack(args, &s->tcp) >= 0) {
                    s->tcp.time = time(NULL);
                    s->tcp.local_seq++;
                    s->tcp.state = TCP_SYN_RECV;
                }
            }
        } else {
            int fwd = 0;
            if (ev->events & EPOLLOUT) {
                uint32_t buffer_size = get_receive_buffer(s);
                while (s->tcp.forward != NULL && s->tcp.forward->seq == s->tcp.remote_seq && s->tcp.forward->len - s->tcp.forward->sent < buffer_size) {
                    ssize_t sent = send(s->socket, s->tcp.forward->data + s->tcp.forward->sent, s->tcp.forward->len - s->tcp.forward->sent, (unsigned int) (MSG_NOSIGNAL | (s->tcp.forward->psh ? 0 : MSG_MORE)));
                    if (sent < 0) {
                        if (errno == EINTR || errno == EAGAIN)
                            break;
                        else {
                            write_rst(args, &s->tcp);
                            break;
                        }
                    } else {
                        fwd = 1;
                        buffer_size -= sent;
                        s->tcp.sent += sent;
                        s->tcp.forward->sent += sent;

                        if (s->tcp.forward->len == s->tcp.forward->sent) {
                            s->tcp.remote_seq = s->tcp.forward->seq + s->tcp.forward->sent;
                            struct segment *p = s->tcp.forward;
                            s->tcp.forward = s->tcp.forward->next;
                            ng_free(p->data, __FILE__, __LINE__);
                            ng_free(p, __FILE__, __LINE__);
                        } else
                            break;
                    }
                }
            }

            uint32_t window = get_receive_window(s);
            uint32_t prev = s->tcp.recv_window;
            s->tcp.recv_window = window;

            if (fwd || (prev == 0 && window > 0)) {
                if (fwd && s->tcp.forward == NULL && s->tcp.state == TCP_CLOSE_WAIT)
                    s->tcp.remote_seq++;
                if (write_ack(args, &s->tcp) >= 0)
                    s->tcp.time = time(NULL);
            }

            if (s->tcp.state == TCP_ESTABLISHED || s->tcp.state == TCP_CLOSE_WAIT) {
                uint32_t send_window = get_send_window(&s->tcp);
                if ((ev->events & EPOLLIN) && send_window > 0) {
                    s->tcp.time = time(NULL);
                    uint32_t buffer_size = (send_window > s->tcp.mss ? s->tcp.mss : send_window);
                    uint8_t *buffer = ng_malloc(buffer_size, "tcp socket");
                    ssize_t bytes = recv(s->socket, buffer, (size_t) buffer_size, 0);
                    if (bytes < 0) {
                        if (errno != EINTR && errno != EAGAIN)
                            write_rst(args, &s->tcp);
                    } else if (bytes == 0) {
                        if (s->tcp.forward == NULL) {
                            if (write_fin_ack(args, &s->tcp) >= 0)
                                s->tcp.local_seq++;

                            if (s->tcp.state == TCP_ESTABLISHED)
                                s->tcp.state = TCP_FIN_WAIT1;
                            else if (s->tcp.state == TCP_CLOSE_WAIT)
                                s->tcp.state = TCP_LAST_ACK;
                        } else
                            write_rst(args, &s->tcp);

                        if (s->socket >= 0) {
                            if (close(s->socket) == 0) {
                                s->socket = -1;
                            } else {
                                log_android(ANDROID_LOG_WARN, "Failed to close socket %d: %s", s->socket, strerror(errno));
                                s->socket = -1;
                            }
                        }
                    } else {
                        s->tcp.received += bytes;
                        if (write_data(args, &s->tcp, buffer, (size_t) bytes) >= 0) {
                            s->tcp.local_seq += bytes;
                            s->tcp.unconfirmed++;
                        }
                    }
                    ng_free(buffer, __FILE__, __LINE__);
                }
            }
        }
    }

    if (s->tcp.state != oldstate || s->tcp.local_seq != oldlocal || s->tcp.remote_seq != oldremote)
        return;
}

jboolean handle_tcp(const struct arguments *args, const uint8_t *pkt, size_t length, const uint8_t *payload, int uid, int allowed, struct allowed *redirect, const int epoll_fd) {
    const uint8_t version = (*pkt) >> 4;
    const struct iphdr *ip4 = (struct iphdr *) pkt;
    const struct ip6_hdr *ip6 = (struct ip6_hdr *) pkt;
    const struct tcphdr *tcphdr = (struct tcphdr *) payload;
    const uint8_t tcpoptlen = (uint8_t) ((tcphdr->doff - 5) * 4);
    const uint8_t *tcpoptions = payload + sizeof(struct tcphdr);
    const uint8_t *data = payload + sizeof(struct tcphdr) + tcpoptlen;
    const uint16_t datalen = (const uint16_t) (length - (data - pkt));

    struct ng_session *cur = args->ctx->ng_session;
    while (cur != NULL && !(cur->protocol == IPPROTO_TCP && cur->tcp.version == version && cur->tcp.source == tcphdr->source && cur->tcp.dest == tcphdr->dest && (version == 4 ? cur->tcp.saddr.ip4 == ip4->saddr && cur->tcp.daddr.ip4 == ip4->daddr : memcmp(&cur->tcp.saddr.ip6, &ip6->ip6_src, 16) == 0 && memcmp(&cur->tcp.daddr.ip6, &ip6->ip6_dst, 16) == 0)))
        cur = cur->next;

    char source[INET6_ADDRSTRLEN + 1];
    char dest[INET6_ADDRSTRLEN + 1];
    if (version == 4) {
        inet_ntop(AF_INET, &ip4->saddr, source, sizeof(source));
        inet_ntop(AF_INET, &ip4->daddr, dest, sizeof(dest));
    } else {
        inet_ntop(AF_INET6, &ip6->ip6_src, source, sizeof(source));
        inet_ntop(AF_INET6, &ip6->ip6_dst, dest, sizeof(dest));
    }

    if (tcphdr->urg)
        return 1;

    if (cur == NULL) {
        if (tcphdr->syn) {
            uint16_t mss = get_default_mss(version);
            uint8_t ws = 0;
            int optlen = tcpoptlen;
            uint8_t *options = (uint8_t *) tcpoptions;
            while (optlen > 0) {
                uint8_t kind = *options;
                uint8_t len = *(options + 1);
                if (kind == 0)
                    break;

                if (kind == 2 && len == 4)
                    mss = ntohs(*((uint16_t *) (options + 2)));
                else if (kind == 3 && len == 3)
                    ws = *(options + 2);

                if (kind == 1) {
                    optlen--;
                    options++;
                } else {
                    optlen -= len;
                    options += len;
                }
            }

            struct ng_session *s = ng_malloc(sizeof(struct ng_session), "tcp session");
            s->protocol = IPPROTO_TCP;

            s->tcp.time = time(NULL);
            s->tcp.uid = uid;
            s->tcp.version = version;
            s->tcp.mss = mss;
            s->tcp.recv_scale = ws;
            s->tcp.send_scale = ws;
            s->tcp.send_window = ((uint32_t) ntohs(tcphdr->window)) << s->tcp.send_scale;
            s->tcp.unconfirmed = 0;
            s->tcp.remote_seq = ntohl(tcphdr->seq);
            s->tcp.local_seq = (uint32_t) rand();
            s->tcp.remote_start = s->tcp.remote_seq;
            s->tcp.local_start = s->tcp.local_seq;
            s->tcp.acked = 0;
            s->tcp.last_keep_alive = 0;
            s->tcp.created = get_ms();
            s->tcp.sent = 0;
            s->tcp.received = 0;

            if (version == 4) {
                s->tcp.saddr.ip4 = (__be32) ip4->saddr;
                s->tcp.daddr.ip4 = (__be32) ip4->daddr;
            } else {
                memcpy(&s->tcp.saddr.ip6, &ip6->ip6_src, 16);
                memcpy(&s->tcp.daddr.ip6, &ip6->ip6_dst, 16);
            }

            s->tcp.source = tcphdr->source;
            s->tcp.dest = tcphdr->dest;
            s->tcp.state = TCP_LISTEN;
            s->tcp.socks5 = SOCKS5_NONE;
            s->tcp.forward = NULL;
            s->next = NULL;

            if (datalen) {
                s->tcp.forward = ng_malloc(sizeof(struct segment), "syn segment");
                s->tcp.forward->seq = s->tcp.remote_seq;
                s->tcp.forward->len = datalen;
                s->tcp.forward->sent = 0;
                s->tcp.forward->psh = tcphdr->psh;
                s->tcp.forward->data = ng_malloc(datalen, "syn segment data");
                memcpy(s->tcp.forward->data, data, datalen);
                s->tcp.forward->next = NULL;
            }

            s->socket = open_tcp_socket(args, &s->tcp, redirect);
            if (s->socket < 0) {
                // The upstream connection failed at once (no network during a switch: ENETUNREACH). Refuse the SYN
                // now, as the app's own connect() would fail without a VPN, instead of leaving it to its SYN
                // retransmits (1, 2, 4 s ...). RFC 9293 3.10.7.1, reply to a segment without ACK:
                // <SEQ=0><ACK=SEG.SEQ+SEG.LEN><CTL=RST,ACK>. write_rst sets ACK and adds 1 for the SYN in LISTEN.
                int err = errno;
                s->tcp.local_seq = 0;
                s->tcp.remote_seq += datalen;
                s->tcp.recv_window = 0;
                write_rst(args, &s->tcp);
                log_android(ANDROID_LOG_DEBUG, "TCP upstream connection to port %d failed at once (%d: %s), refused the app's SYN with an RST",
                            ntohs(s->tcp.dest), err, strerror(err));
                // The SYN's queued data too, not only the session
                clear_tcp_data(&s->tcp);
                ng_free(s, __FILE__, __LINE__);
                return 0;
            }

            s->tcp.recv_window = get_receive_window(s);

            memset(&s->ev, 0, sizeof(struct epoll_event));
            s->ev.events = EPOLLOUT | EPOLLERR;
            s->ev.data.ptr = s;
            if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, s->socket, &s->ev)) {
                // Not linked into the session list yet: close and free everything here, or it leaks
                log_android(ANDROID_LOG_ERROR, "epoll add TCP socket %d error %d: %s", s->socket, errno, strerror(errno));
                close(s->socket);
                clear_tcp_data(&s->tcp);
                ng_free(s, __FILE__, __LINE__);
                return 0;
            }

            s->next = args->ctx->ng_session;
            args->ctx->ng_session = s;

            if (!allowed)
                write_rst(args, &s->tcp);
        } else {
            struct tcp_session rst;
            memset(&rst, 0, sizeof(struct tcp_session));
            rst.version = version;
            rst.local_seq = ntohl(tcphdr->ack_seq);
            rst.remote_seq = ntohl(tcphdr->seq) + datalen + (tcphdr->syn || tcphdr->fin ? 1 : 0);

            if (version == 4) {
                rst.saddr.ip4 = (__be32) ip4->saddr;
                rst.daddr.ip4 = (__be32) ip4->daddr;
            } else {
                memcpy(&rst.saddr.ip6, &ip6->ip6_src, 16);
                memcpy(&rst.daddr.ip6, &ip6->ip6_dst, 16);
            }

            rst.source = tcphdr->source;
            rst.dest = tcphdr->dest;

            write_rst(args, &rst);
            return 0;
        }
    } else {
        if (cur->tcp.state == TCP_CLOSING || cur->tcp.state == TCP_CLOSE) {
            write_rst(args, &cur->tcp);
            return 0;
        } else {
            int oldstate = cur->tcp.state;
            uint32_t oldlocal = cur->tcp.local_seq;
            uint32_t oldremote = cur->tcp.remote_seq;

            if (!tcphdr->syn)
                cur->tcp.time = time(NULL);
            cur->tcp.send_window = ((uint32_t) ntohs(tcphdr->window)) << cur->tcp.send_scale;
            cur->tcp.unconfirmed = 0;

            if (datalen) {
                if (cur->socket < 0) {
                    write_rst(args, &cur->tcp);
                    return 0;
                }
                if (cur->tcp.state == TCP_CLOSE_WAIT) {
                    write_rst(args, &cur->tcp);
                    return 0;
                }
                queue_tcp(args, tcphdr, source, &cur->tcp, data, datalen);
            }

            if (tcphdr->rst) {
                cur->tcp.state = TCP_CLOSING;
                return 0;
            } else {
                if (!tcphdr->ack || ntohl(tcphdr->ack_seq) == cur->tcp.local_seq) {
                    if (tcphdr->syn) {
                    } else if (tcphdr->fin) {
                        if (cur->tcp.state == TCP_ESTABLISHED) {
                            if (cur->tcp.forward == NULL) {
                                cur->tcp.remote_seq++;
                                if (write_ack(args, &cur->tcp) >= 0)
                                    cur->tcp.state = TCP_CLOSE_WAIT;
                            } else
                                cur->tcp.state = TCP_CLOSE_WAIT;
                        } else if (cur->tcp.state == TCP_CLOSE_WAIT) {
                        } else if (cur->tcp.state == TCP_FIN_WAIT1) {
                            cur->tcp.remote_seq++;
                            if (write_ack(args, &cur->tcp) >= 0)
                                cur->tcp.state = TCP_CLOSE;
                        } else
                            return 0;
                    } else if (tcphdr->ack) {
                        cur->tcp.acked = ntohl(tcphdr->ack_seq);

                        if (cur->tcp.state == TCP_SYN_RECV)
                            cur->tcp.state = TCP_ESTABLISHED;
                        else if (cur->tcp.state == TCP_ESTABLISHED) {
                        } else if (cur->tcp.state == TCP_LAST_ACK)
                            cur->tcp.state = TCP_CLOSING;
                        else if (cur->tcp.state == TCP_CLOSE_WAIT) {
                        } else if (cur->tcp.state == TCP_FIN_WAIT1) {
                        } else
                            return 0;
                    } else
                        return 0;
                } else {
                    uint32_t ack = ntohl(tcphdr->ack_seq);
                    if ((uint32_t) (ack + 1) == cur->tcp.local_seq) {
                        if (cur->tcp.state == TCP_ESTABLISHED) {
                            int on = 1;
                            setsockopt(cur->socket, SOL_SOCKET, SO_KEEPALIVE, &on, sizeof(on));
                        }
                    } else if (compare_u32(ack, cur->tcp.local_seq) < 0) {
                        if (compare_u32(ack, cur->tcp.acked) <= 0)
                            return 1;
                        else {
                            cur->tcp.acked = ack;
                            return 1;
                        }
                    } else {
                        write_rst(args, &cur->tcp);
                        return 0;
                    }
                }
            }
        }
    }

    return 1;
}

// Closes an upstream socket that could not be set up and returns -1, keeping the errno of the failure
static int close_failed_socket(int sock, const char *what) {
    int err = errno;
    log_android(ANDROID_LOG_DEBUG, "TCP upstream %s error %d: %s, closing socket %d", what, err, strerror(err), sock);
    close(sock);
    errno = err;
    return -1;
}

int open_tcp_socket(const struct arguments *args, const struct tcp_session *cur, const struct allowed *redirect) {
    int sock;
    int version;
    if (redirect == NULL) {
        if (*socks5_addr && socks5_port)
            version = (strstr(socks5_addr, ":") == NULL ? 4 : 6);
        else
            version = cur->version;
    } else
        version = (strstr(redirect->raddr, ":") == NULL ? 4 : 6);

    if ((sock = socket(version == 4 ? PF_INET : PF_INET6, SOCK_STREAM, 0)) < 0)
        return -1;

    // Every failure below closes the socket: the caller only frees the session. A connect() that fails at once
    // (ENETUNREACH while there is no network, during a Wi-Fi switch) leaked one fd per SYN before.
    int on = 1;
    if (setsockopt(sock, SOL_TCP, TCP_NODELAY, &on, sizeof(on)) < 0)
        return close_failed_socket(sock, "setsockopt TCP_NODELAY");

    int flags = fcntl(sock, F_GETFL, 0);
    if (flags < 0 || fcntl(sock, F_SETFL, flags | O_NONBLOCK) < 0)
        return close_failed_socket(sock, "fcntl O_NONBLOCK");

    struct sockaddr_in addr4;
    struct sockaddr_in6 addr6;
    if (redirect == NULL) {
        if (*socks5_addr && socks5_port) {
            if (version == 4) {
                addr4.sin_family = AF_INET;
                inet_pton(AF_INET, socks5_addr, &addr4.sin_addr);
                addr4.sin_port = htons(socks5_port);
            } else {
                addr6.sin6_family = AF_INET6;
                inet_pton(AF_INET6, socks5_addr, &addr6.sin6_addr);
                addr6.sin6_port = htons(socks5_port);
            }
        } else {
            if (version == 4) {
                addr4.sin_family = AF_INET;
                addr4.sin_addr.s_addr = (__be32) cur->daddr.ip4;
                addr4.sin_port = cur->dest;
            } else {
                addr6.sin6_family = AF_INET6;
                memcpy(&addr6.sin6_addr, &cur->daddr.ip6, 16);
                addr6.sin6_port = cur->dest;
            }
        }
    } else {
        if (version == 4) {
            addr4.sin_family = AF_INET;
            inet_pton(AF_INET, redirect->raddr, &addr4.sin_addr);
            addr4.sin_port = htons(redirect->rport);
        } else {
            addr6.sin6_family = AF_INET6;
            inet_pton(AF_INET6, redirect->raddr, &addr6.sin6_addr);
            addr6.sin6_port = htons(redirect->rport);
        }
    }

    int err = connect(sock, (version == 4 ? (const struct sockaddr *) &addr4 : (const struct sockaddr *) &addr6), (socklen_t) (version == 4 ? sizeof(struct sockaddr_in) : sizeof(struct sockaddr_in6)));
    if (err < 0 && errno != EINPROGRESS)
        return close_failed_socket(sock, "connect");

    return sock;
}

int write_ack(const struct arguments *args, struct tcp_session *cur) {
    if (write_tcp(args, cur, NULL, 0, 0, 1, 0, 0) < 0) {
        cur->state = TCP_CLOSING;
        return -1;
    }
    return 0;
}

void write_rst(const struct arguments *args, struct tcp_session *cur) {
    int ack = 0;
    if (cur->state == TCP_LISTEN) {
        ack = 1;
        cur->remote_seq++;
    }
    write_tcp(args, cur, NULL, 0, 0, ack, 0, 1);
    if (cur->state != TCP_CLOSE)
        cur->state = TCP_CLOSING;
}

ssize_t write_tcp(const struct arguments *args, const struct tcp_session *cur, const uint8_t *data, size_t datalen, int syn, int ack, int fin, int rst) {
    size_t len;
    u_int8_t *buffer;
    struct tcphdr *tcp;
    uint16_t csum;
    char source[INET6_ADDRSTRLEN + 1];
    char dest[INET6_ADDRSTRLEN + 1];

    int optlen = (syn ? 4 + 3 + 1 : 0);
    uint8_t *options;
    if (cur->version == 4) {
        len = sizeof(struct iphdr) + sizeof(struct tcphdr) + optlen + datalen;
        buffer = ng_malloc(len, "tcp write4");
        struct iphdr *ip4 = (struct iphdr *) buffer;
        tcp = (struct tcphdr *) (buffer + sizeof(struct iphdr));
        options = buffer + sizeof(struct iphdr) + sizeof(struct tcphdr);
        if (datalen)
            memcpy(buffer + sizeof(struct iphdr) + sizeof(struct tcphdr) + optlen, data, datalen);

        memset(ip4, 0, sizeof(struct iphdr));
        ip4->version = 4;
        ip4->ihl = sizeof(struct iphdr) >> 2;
        ip4->tot_len = htons(len);
        ip4->ttl = IPDEFTTL;
        ip4->protocol = IPPROTO_TCP;
        ip4->saddr = cur->daddr.ip4;
        ip4->daddr = cur->saddr.ip4;

        ip4->check = ~calc_checksum(0, (uint8_t *) ip4, sizeof(struct iphdr));

        struct ippseudo pseudo;
        memset(&pseudo, 0, sizeof(struct ippseudo));
        pseudo.ippseudo_src.s_addr = (__be32) ip4->saddr;
        pseudo.ippseudo_dst.s_addr = (__be32) ip4->daddr;
        pseudo.ippseudo_p = ip4->protocol;
        pseudo.ippseudo_len = htons(sizeof(struct tcphdr) + optlen + datalen);

        csum = calc_checksum(0, (uint8_t *) &pseudo, sizeof(struct ippseudo));
    } else {
        len = sizeof(struct ip6_hdr) + sizeof(struct tcphdr) + optlen + datalen;
        buffer = ng_malloc(len, "tcp write 6");
        struct ip6_hdr *ip6 = (struct ip6_hdr *) buffer;
        tcp = (struct tcphdr *) (buffer + sizeof(struct ip6_hdr));
        options = buffer + sizeof(struct ip6_hdr) + sizeof(struct tcphdr);
        if (datalen)
            memcpy(buffer + sizeof(struct ip6_hdr) + sizeof(struct tcphdr) + optlen, data, datalen);

        memset(ip6, 0, sizeof(struct ip6_hdr));
        ip6->ip6_ctlun.ip6_un1.ip6_un1_plen = htons(len - sizeof(struct ip6_hdr));
        ip6->ip6_ctlun.ip6_un1.ip6_un1_nxt = IPPROTO_TCP;
        ip6->ip6_ctlun.ip6_un1.ip6_un1_hlim = IPDEFTTL;
        ip6->ip6_ctlun.ip6_un2_vfc = 0x60;
        memcpy(&(ip6->ip6_src), &cur->daddr.ip6, 16);
        memcpy(&(ip6->ip6_dst), &cur->saddr.ip6, 16);

        struct ip6_hdr_pseudo pseudo;
        memset(&pseudo, 0, sizeof(struct ip6_hdr_pseudo));
        memcpy(&pseudo.ip6ph_src, &ip6->ip6_dst, 16);
        memcpy(&pseudo.ip6ph_dst, &ip6->ip6_src, 16);
        pseudo.ip6ph_len = ip6->ip6_ctlun.ip6_un1.ip6_un1_plen;
        pseudo.ip6ph_nxt = ip6->ip6_ctlun.ip6_un1.ip6_un1_nxt;

        csum = calc_checksum(0, (uint8_t *) &pseudo, sizeof(struct ip6_hdr_pseudo));
    }

    memset(tcp, 0, sizeof(struct tcphdr));
    tcp->source = cur->dest;
    tcp->dest = cur->source;
    tcp->seq = htonl(cur->local_seq);
    tcp->ack_seq = htonl((uint32_t) (cur->remote_seq));
    tcp->doff = (__u16) ((sizeof(struct tcphdr) + optlen) >> 2);
    tcp->syn = (__u16) syn;
    tcp->ack = (__u16) ack;
    tcp->fin = (__u16) fin;
    tcp->rst = (__u16) rst;
    tcp->window = htons(cur->recv_window >> cur->recv_scale);

    if (!tcp->ack)
        tcp->ack_seq = 0;

    if (syn) {
        *(options) = 2;
        *(options + 1) = 4;
        *((uint16_t *) (options + 2)) = get_default_mss(cur->version);

        *(options + 4) = 3;
        *(options + 5) = 3;
        *(options + 6) = cur->recv_scale;

        *(options + 7) = 0;
    }

    csum = calc_checksum(csum, (uint8_t *) tcp, sizeof(struct tcphdr));
    csum = calc_checksum(csum, options, (size_t) optlen);
    csum = calc_checksum(csum, data, datalen);
    tcp->check = ~csum;

    inet_ntop(cur->version == 4 ? AF_INET : AF_INET6, cur->version == 4 ? (const void *) &cur->saddr.ip4 : (const void *) &cur->saddr.ip6, source, sizeof(source));
    inet_ntop(cur->version == 4 ? AF_INET : AF_INET6, cur->version == 4 ? (const void *) &cur->daddr.ip4 : (const void *) &cur->daddr.ip6, dest, sizeof(dest));

    ssize_t res = write(args->tun, buffer, len);

    ng_free(buffer, __FILE__, __LINE__);

    if (res != len)
        return -1;

    return res;
}