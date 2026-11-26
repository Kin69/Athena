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

int get_udp_timeout(const struct udp_session *u, int sessions, int maxsessions) {
    int timeout = (ntohs(u->dest) == 53 ? UDP_TIMEOUT_53 : UDP_TIMEOUT_ANY);
    int scale = 100 - sessions * 100 / maxsessions;
    return timeout * scale / 100;
}

int check_udp_session(const struct arguments *args, struct ng_session *s, int sessions, int maxsessions) {
    time_t now = time(NULL);

    char source[INET6_ADDRSTRLEN + 1];
    char dest[INET6_ADDRSTRLEN + 1];
    if (s->udp.version == 4) {
        inet_ntop(AF_INET, &s->udp.saddr.ip4, source, sizeof(source));
        inet_ntop(AF_INET, &s->udp.daddr.ip4, dest, sizeof(dest));
    } else {
        inet_ntop(AF_INET6, &s->udp.saddr.ip6, source, sizeof(source));
        inet_ntop(AF_INET6, &s->udp.daddr.ip6, dest, sizeof(dest));
    }

    int timeout = get_udp_timeout(&s->udp, sessions, maxsessions);

    // A forwarded DNS query without any answer: the DNS server is unreachable over the current network
    if (s->udp.state == UDP_ACTIVE && s->udp.dns_sent > 0 &&
        (get_ms() - s->udp.dns_sent >= DNS_ANSWER_TIMEOUT * 1000 || s->udp.time + timeout < now)) {
        args->ctx->dns_unanswered++;
        log_android(ANDROID_LOG_INFO, "Forwarded DNS query got no answer in %lld ms, %d in a row", get_ms() - s->udp.dns_sent, args->ctx->dns_unanswered);
        report_upstream_dns(args, JNI_FALSE, s->udp.dns_sent);
        s->udp.dns_sent = 0;
    }

    if (s->udp.state == UDP_ACTIVE && s->udp.time + timeout < now) {
        s->udp.state = UDP_FINISHING;
    }

    if (s->udp.state == UDP_FINISHING) {
        if (s->socket >= 0) {
            if (close(s->socket) != 0) {
                log_android(ANDROID_LOG_WARN, "Failed to close UDP socket %d: %s", s->socket, strerror(errno));
                s->socket = -1;
                return -1;
            }
            s->socket = -1;
        }
        s->udp.time = time(NULL);
        s->udp.state = UDP_CLOSED;
    }

    if (s->udp.state == UDP_CLOSED && (s->udp.sent || s->udp.received)) {
        s->udp.sent = 0;
        s->udp.received = 0;
    }

    if ((s->udp.state == UDP_CLOSED) && s->udp.time + UDP_KEEP_TIMEOUT < now)
        return 1;

    return 0;
}

void check_udp_socket(const struct arguments *args, const struct epoll_event *ev) {
    struct ng_session *s = (struct ng_session *) ev->data.ptr;

    if (ev->events & EPOLLERR) {
        s->udp.time = time(NULL);
        int serr = 0;
        socklen_t optlen = sizeof(int);
        getsockopt(s->socket, SOL_SOCKET, SO_ERROR, &serr, &optlen);
        s->udp.state = UDP_FINISHING;
    } else if (ev->events & EPOLLIN) {
        s->udp.time = time(NULL);
        uint8_t *buffer = ng_malloc(s->udp.mss, "udp recv");
        ssize_t bytes = recv(s->socket, buffer, s->udp.mss, 0);

        if (bytes < 0) {
            if (errno != EINTR && errno != EAGAIN)
                s->udp.state = UDP_FINISHING;
        } else if (bytes == 0) {
            s->udp.state = UDP_FINISHING;
        } else {
            s->udp.received += bytes;
            if (s->udp.dns_forwarded) {
                s->udp.dns_sent = 0;
                if (args->ctx->dns_unanswered > 0) {
                    log_android(ANDROID_LOG_INFO, "Forwarded DNS answered again after %d unanswered queries", args->ctx->dns_unanswered);
                    args->ctx->dns_unanswered = 0;
                    report_upstream_dns(args, JNI_TRUE, 0);
                }
            }
            if (write_udp(args, &s->udp, buffer, (size_t) bytes) < 0)
                s->udp.state = UDP_FINISHING;
            else if (ntohs(s->udp.dest) == 53)
                s->udp.state = UDP_FINISHING;
        }
        ng_free(buffer, __FILE__, __LINE__);
    }
}

jboolean handle_udp(const struct arguments *args, const uint8_t *pkt, size_t length, const uint8_t *payload, int uid, struct allowed *redirect, const int epoll_fd) {
    const uint8_t version = (*pkt) >> 4;
    const struct iphdr *ip4 = (struct iphdr *) pkt;
    const struct ip6_hdr *ip6 = (struct ip6_hdr *) pkt;
    const struct udphdr *udphdr = (struct udphdr *) payload;
    const uint8_t *data = payload + sizeof(struct udphdr);
    const size_t datalen = length - (data - pkt);

    struct ng_session *cur = args->ctx->ng_session;
    while (cur != NULL && !(cur->protocol == IPPROTO_UDP && cur->udp.version == version &&
                            cur->udp.source == udphdr->source && cur->udp.dest == udphdr->dest &&
                            (version == 4 ? cur->udp.saddr.ip4 == ip4->saddr && cur->udp.daddr.ip4 == ip4->daddr :
                             memcmp(&cur->udp.saddr.ip6, &ip6->ip6_src, 16) == 0 && memcmp(&cur->udp.daddr.ip6, &ip6->ip6_dst, 16) == 0)))
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

    if (cur != NULL && cur->udp.state != UDP_ACTIVE)
        return 0;

    if (cur == NULL) {
        struct ng_session *s = ng_malloc(sizeof(struct ng_session), "udp session");
        s->protocol = IPPROTO_UDP;
        s->udp.time = time(NULL);
        s->udp.uid = uid;
        s->udp.version = version;

        int rversion = (redirect == NULL ? s->udp.version : (strstr(redirect->raddr, ":") == NULL ? 4 : 6));
        s->udp.mss = (uint16_t) (rversion == 4 ? UDP4_MAXMSG : UDP6_MAXMSG);
        s->udp.sent = 0;
        s->udp.received = 0;
        s->udp.dns_forwarded = 0;
        s->udp.dns_sent = 0;

        if (version == 4) {
            s->udp.saddr.ip4 = (__be32) ip4->saddr;
            s->udp.daddr.ip4 = (__be32) ip4->daddr;

            // Check for DNS redirection: 198.18.0.1 -> user's DNS server
            uint32_t target_ip = htonl(0xC6120001); // 198.18.0.1 in network byte order

            if (s->udp.daddr.ip4 == target_ip && ntohs(udphdr->dest) == 53) {
                // Convert user's DNS server from string to network byte order
                struct in_addr dns_addr;
                if (inet_pton(AF_INET, args->ctx->dns_server_v4, &dns_addr) == 1) {
                    s->udp.daddr.ip4 = dns_addr.s_addr;
                } else {
                    // Fallback to 9.9.9.9 if parsing fails
                    s->udp.daddr.ip4 = htonl(0x09090909);
                    log_android(ANDROID_LOG_WARN, "Failed to parse DNS IPv4, using fallback: 9.9.9.9");
                }
                s->udp.dns_forwarded = 1;
                s->udp.dns_sent = get_ms();
            }
        } else {
            memcpy(&s->udp.saddr.ip6, &ip6->ip6_src, 16);
            memcpy(&s->udp.daddr.ip6, &ip6->ip6_dst, 16);

            // Check for IPv6 DNS redirection: fd00::53 -> user's IPv6 DNS server
            struct in6_addr target_ipv6;
            inet_pton(AF_INET6, "fd00::53", &target_ipv6);

            if (memcmp(&s->udp.daddr.ip6, &target_ipv6, 16) == 0 && ntohs(udphdr->dest) == 53) {
                // Convert user's IPv6 DNS server from string
                struct in6_addr dns_addr6;
                if (inet_pton(AF_INET6, args->ctx->dns_server_v6, &dns_addr6) == 1) {
                    memcpy(&s->udp.daddr.ip6, &dns_addr6, 16);
                    log_android(ANDROID_LOG_DEBUG, "DNS IPv6 redirected to: %s", args->ctx->dns_server_v6);
                } else {
                    // Fallback to 2620:fe::fe if parsing fails
                    inet_pton(AF_INET6, "2620:fe::fe", &dns_addr6);
                    memcpy(&s->udp.daddr.ip6, &dns_addr6, 16);
                    log_android(ANDROID_LOG_WARN, "Failed to parse DNS IPv6, using fallback: 2620:fe::fe");
                }
                s->udp.dns_forwarded = 1;
                s->udp.dns_sent = get_ms();
            }
        }

        s->udp.source = udphdr->source;
        s->udp.dest = udphdr->dest;
        s->udp.state = UDP_ACTIVE;
        s->next = NULL;

        s->socket = open_udp_socket(args, &s->udp, redirect);
        if (s->socket < 0) {
            ng_free(s, __FILE__, __LINE__);
            return 0;
        }

        memset(&s->ev, 0, sizeof(struct epoll_event));
        s->ev.events = EPOLLIN | EPOLLERR;
        s->ev.data.ptr = s;
        if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, s->socket, &s->ev))
            return -1;

        s->next = args->ctx->ng_session;
        args->ctx->ng_session = s;
        cur = s;
    }

    cur->udp.time = time(NULL);

    int rversion;
    struct sockaddr_in addr4;
    struct sockaddr_in6 addr6;
    if (redirect == NULL) {
        rversion = cur->udp.version;
        if (cur->udp.version == 4) {
            addr4.sin_family = AF_INET;
            addr4.sin_addr.s_addr = (__be32) cur->udp.daddr.ip4;
            addr4.sin_port = cur->udp.dest;
        } else {
            addr6.sin6_family = AF_INET6;
            memcpy(&addr6.sin6_addr, &cur->udp.daddr.ip6, 16);
            addr6.sin6_port = cur->udp.dest;
        }
    } else {
        rversion = (strstr(redirect->raddr, ":") == NULL ? 4 : 6);
        if (rversion == 4) {
            addr4.sin_family = AF_INET;
            inet_pton(AF_INET, redirect->raddr, &addr4.sin_addr);
            addr4.sin_port = htons(redirect->rport);
        } else {
            addr6.sin6_family = AF_INET6;
            inet_pton(AF_INET6, redirect->raddr, &addr6.sin6_addr);
            addr6.sin6_port = htons(redirect->rport);
        }
    }

    if (sendto(cur->socket, data, (socklen_t) datalen, MSG_NOSIGNAL,
               (rversion == 4 ? (const struct sockaddr *) &addr4 : (const struct sockaddr *) &addr6),
               (socklen_t) (rversion == 4 ? sizeof(addr4) : sizeof(addr6))) != datalen) {
        if (errno != EINTR && errno != EAGAIN) {
            cur->udp.state = UDP_FINISHING;
            return 0;
        }
    } else
        cur->udp.sent += datalen;

    return 1;
}

int open_udp_socket(const struct arguments *args, const struct udp_session *cur, const struct allowed *redirect) {
    int sock;
    int version = (redirect == NULL ? cur->version : (strstr(redirect->raddr, ":") == NULL ? 4 : 6));

    sock = socket(version == 4 ? PF_INET : PF_INET6, SOCK_DGRAM, IPPROTO_UDP);
    if (sock < 0)
        return -1;

    if (cur->version == 4) {
        uint32_t broadcast4 = INADDR_BROADCAST;
        if (memcmp(&cur->daddr.ip4, &broadcast4, sizeof(broadcast4)) == 0) {
            int on = 1;
            setsockopt(sock, SOL_SOCKET, SO_BROADCAST, &on, sizeof(on));
        }
    } else if (*((uint8_t *) &cur->daddr.ip6) == 0xFF) {
        int loop = 1;
        setsockopt(sock, IPPROTO_IPV6, IPV6_MULTICAST_LOOP, &loop, sizeof(loop));

        int ttl = -1;
        setsockopt(sock, IPPROTO_IPV6, IPV6_MULTICAST_HOPS, &ttl, sizeof(ttl));

        struct ipv6_mreq mreq6;
        memcpy(&mreq6.ipv6mr_multiaddr, &cur->daddr.ip6, sizeof(struct in6_addr));
        mreq6.ipv6mr_interface = INADDR_ANY;
        setsockopt(sock, IPPROTO_IPV6, IPV6_ADD_MEMBERSHIP, &mreq6, sizeof(mreq6));
    }

    return sock;
}

ssize_t write_udp(const struct arguments *args, const struct udp_session *cur, uint8_t *data, size_t datalen) {
    size_t len;
    u_int8_t *buffer;
    struct udphdr *udp;
    uint16_t csum;

    if (cur->version == 4) {
        len = sizeof(struct iphdr) + sizeof(struct udphdr) + datalen;
        buffer = ng_malloc(len, "udp write4");
        struct iphdr *ip4 = (struct iphdr *) buffer;
        udp = (struct udphdr *) (buffer + sizeof(struct iphdr));
        if (datalen)
            memcpy(buffer + sizeof(struct iphdr) + sizeof(struct udphdr), data, datalen);

        memset(ip4, 0, sizeof(struct iphdr));
        ip4->version = 4;
        ip4->ihl = sizeof(struct iphdr) >> 2;
        ip4->tot_len = htons(len);
        ip4->ttl = IPDEFTTL;
        ip4->protocol = IPPROTO_UDP;
        // Check if this is a response from redirected DNS (user's DNS -> 198.18.0.1)
        uint32_t target_ip = htonl(0xC6120001); // 198.18.0.1 in network byte order

        // Parse user's DNS server to check if this is a DNS response
        struct in_addr user_dns_addr;
        int is_dns_response = 0;
        if (inet_pton(AF_INET, args->ctx->dns_server_v4, &user_dns_addr) == 1) {
            is_dns_response = (cur->daddr.ip4 == user_dns_addr.s_addr && ntohs(cur->dest) == 53);
        }

        if (is_dns_response) {
            ip4->saddr = target_ip;
        } else {
            ip4->saddr = cur->daddr.ip4;
        }
        ip4->daddr = cur->saddr.ip4;
        ip4->check = ~calc_checksum(0, (uint8_t *) ip4, sizeof(struct iphdr));

        struct ippseudo pseudo;
        memset(&pseudo, 0, sizeof(struct ippseudo));
        pseudo.ippseudo_src.s_addr = (__be32) ip4->saddr;
        pseudo.ippseudo_dst.s_addr = (__be32) ip4->daddr;
        pseudo.ippseudo_p = ip4->protocol;
        pseudo.ippseudo_len = htons(sizeof(struct udphdr) + datalen);
        csum = calc_checksum(0, (uint8_t *) &pseudo, sizeof(struct ippseudo));
    } else {
        len = sizeof(struct ip6_hdr) + sizeof(struct udphdr) + datalen;
        buffer = ng_malloc(len, "udp write6");
        struct ip6_hdr *ip6 = (struct ip6_hdr *) buffer;
        udp = (struct udphdr *) (buffer + sizeof(struct ip6_hdr));
        if (datalen)
            memcpy(buffer + sizeof(struct ip6_hdr) + sizeof(struct udphdr), data, datalen);

        memset(ip6, 0, sizeof(struct ip6_hdr));
        ip6->ip6_ctlun.ip6_un1.ip6_un1_flow = 0;
        ip6->ip6_ctlun.ip6_un1.ip6_un1_plen = htons(len - sizeof(struct ip6_hdr));
        ip6->ip6_ctlun.ip6_un1.ip6_un1_nxt = IPPROTO_UDP;
        ip6->ip6_ctlun.ip6_un1.ip6_un1_hlim = IPDEFTTL;
        ip6->ip6_ctlun.ip6_un2_vfc = IPV6_VERSION;

        // Check if this is a response from redirected IPv6 DNS (user's DNS -> fd00::53)
        struct in6_addr user_dns_addr6;
        struct in6_addr target_ipv6;
        inet_pton(AF_INET6, "fd00::53", &target_ipv6);

        int is_dns_response_v6 = 0;
        if (inet_pton(AF_INET6, args->ctx->dns_server_v6, &user_dns_addr6) == 1) {
            is_dns_response_v6 = (memcmp(&cur->daddr.ip6, &user_dns_addr6, 16) == 0 && ntohs(cur->dest) == 53);
        }

        if (is_dns_response_v6) {
            memcpy(&(ip6->ip6_src), &target_ipv6, 16);
        } else {
            memcpy(&(ip6->ip6_src), &cur->daddr.ip6, 16);
        }
        memcpy(&(ip6->ip6_dst), &cur->saddr.ip6, 16);

        struct ip6_hdr_pseudo pseudo;
        memset(&pseudo, 0, sizeof(struct ip6_hdr_pseudo));
        memcpy(&pseudo.ip6ph_src, &ip6->ip6_dst, 16);
        memcpy(&pseudo.ip6ph_dst, &ip6->ip6_src, 16);
        pseudo.ip6ph_len = ip6->ip6_ctlun.ip6_un1.ip6_un1_plen;
        pseudo.ip6ph_nxt = ip6->ip6_ctlun.ip6_un1.ip6_un1_nxt;
        csum = calc_checksum(0, (uint8_t *) &pseudo, sizeof(struct ip6_hdr_pseudo));
    }

    memset(udp, 0, sizeof(struct udphdr));
    udp->source = cur->dest;
    udp->dest = cur->source;
    udp->len = htons(sizeof(struct udphdr) + datalen);

    csum = calc_checksum(csum, (uint8_t *) udp, sizeof(struct udphdr));
    csum = calc_checksum(csum, data, datalen);
    udp->check = ~csum;

    ssize_t res = write(args->tun, buffer, len);
    ng_free(buffer, __FILE__, __LINE__);

    if (res != len)
        return -1;

    return res;
}