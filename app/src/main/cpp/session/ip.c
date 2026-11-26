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

int max_tun_msg = 0;
extern int loglevel;

uint16_t get_mtu() {
    return 10000;
}

uint16_t get_default_mss(int version) {
    if (version == 4)
        return (uint16_t) (get_mtu() - sizeof(struct iphdr) - sizeof(struct tcphdr));
    else
        return (uint16_t) (get_mtu() - sizeof(struct ip6_hdr) - sizeof(struct tcphdr));
}

int check_tun(const struct arguments *args, const struct epoll_event *ev, const int epoll_fd, int sessions, int maxsessions) {
    if (ev->events & EPOLLERR) {
        return -1;
    }

    if (ev->events & EPOLLIN) {
        uint8_t *buffer = ng_malloc(get_mtu(), "tun read");
        ssize_t length = read(args->tun, buffer, get_mtu());

        if (length < 0) {
            ng_free(buffer, __FILE__, __LINE__);
            if (errno == EINTR || errno == EAGAIN)
                return 0;
            else
                return -1;
        } else if (length > 0) {
            if (length > max_tun_msg) {
                max_tun_msg = length;
            }

            handle_ip(args, buffer, (size_t) length, epoll_fd, sessions, maxsessions);
            ng_free(buffer, __FILE__, __LINE__);
        } else {
            ng_free(buffer, __FILE__, __LINE__);
            return -1;
        }
    }

    return 0;
}

int is_lower_layer(int protocol) {
    return (protocol == 0 || protocol == 60 || protocol == 43 || protocol == 44 || protocol == 51 || protocol == 50 || protocol == 135);
}

int is_upper_layer(int protocol) {
    return (protocol == IPPROTO_TCP || protocol == IPPROTO_UDP || protocol == IPPROTO_ICMP || protocol == IPPROTO_ICMPV6);
}

int has_udp_session(const struct arguments *args, const uint8_t *pkt, const uint8_t *payload) {
    const uint8_t version = (*pkt) >> 4;
    const struct iphdr *ip4 = (struct iphdr *) pkt;
    const struct ip6_hdr *ip6 = (struct ip6_hdr *) pkt;
    const struct udphdr *udphdr = (struct udphdr *) payload;

    if (ntohs(udphdr->dest) == 53 && !args->fwd53)
        return 1;

    struct ng_session *cur = args->ctx->ng_session;
    while (cur != NULL && !(cur->protocol == IPPROTO_UDP && cur->udp.version == version &&
                            cur->udp.source == udphdr->source && cur->udp.dest == udphdr->dest &&
                            (version == 4 ? cur->udp.saddr.ip4 == ip4->saddr && cur->udp.daddr.ip4 == ip4->daddr :
                             memcmp(&cur->udp.saddr.ip6, &ip6->ip6_src, 16) == 0 && memcmp(&cur->udp.daddr.ip6, &ip6->ip6_dst, 16) == 0)))
        cur = cur->next;

    return (cur != NULL);
}

void handle_ip(const struct arguments *args, const uint8_t *pkt, const size_t length, const int epoll_fd, int sessions, int maxsessions) {
    uint8_t protocol;
    void *saddr;
    void *daddr;
    char source[INET6_ADDRSTRLEN + 1];
    char dest[INET6_ADDRSTRLEN + 1];
    char flags[10];
    char data[16];
    int flen = 0;
    uint8_t *payload;

    uint8_t version = (*pkt) >> 4;
    if (version == 4) {
        if (length < sizeof(struct iphdr)) {
            return;
        }

        struct iphdr *ip4hdr = (struct iphdr *) pkt;
        protocol = ip4hdr->protocol;
        saddr = &ip4hdr->saddr;
        daddr = &ip4hdr->daddr;

        if (ip4hdr->frag_off & IP_MF) {
            return;
        }

        uint8_t ipoptlen = (uint8_t) ((ip4hdr->ihl - 5) * 4);
        payload = (uint8_t *) (pkt + sizeof(struct iphdr) + ipoptlen);

        if (ntohs(ip4hdr->tot_len) != length) {
            return;
        }

        if (loglevel < ANDROID_LOG_WARN) {
            if (!calc_checksum(0, (uint8_t *) ip4hdr, sizeof(struct iphdr))) {
                return;
            }
        }
    } else if (version == 6) {
        if (length < sizeof(struct ip6_hdr)) {
            return;
        }

        struct ip6_hdr *ip6hdr = (struct ip6_hdr *) pkt;
        uint16_t off = 0;
        protocol = ip6hdr->ip6_nxt;

        if (!is_upper_layer(protocol)) {
            off = sizeof(struct ip6_hdr);
            struct ip6_ext *ext = (struct ip6_ext *) (pkt + off);
            while (is_lower_layer(ext->ip6e_nxt) && !is_upper_layer(protocol)) {
                protocol = ext->ip6e_nxt;
                off += (8 + ext->ip6e_len);
                ext = (struct ip6_ext *) (pkt + off);
            }
            if (!is_upper_layer(protocol)) {
                off = 0;
                protocol = ip6hdr->ip6_nxt;
            }
        }

        saddr = &ip6hdr->ip6_src;
        daddr = &ip6hdr->ip6_dst;
        payload = (uint8_t *) (pkt + sizeof(struct ip6_hdr) + off);
    } else {
        return;
    }

    inet_ntop(version == 4 ? AF_INET : AF_INET6, saddr, source, sizeof(source));
    inet_ntop(version == 4 ? AF_INET : AF_INET6, daddr, dest, sizeof(dest));

    int syn = 0;
    uint16_t sport = 0;
    uint16_t dport = 0;
    *data = 0;

    if (protocol == IPPROTO_ICMP || protocol == IPPROTO_ICMPV6) {
        if (length - (payload - pkt) < ICMP_MINLEN) {
            return;
        }

        struct icmp *icmp = (struct icmp *) payload;
        sprintf(data, "type %d/%d", icmp->icmp_type, icmp->icmp_code);
        sport = ntohs(icmp->icmp_id);
        dport = ntohs(icmp->icmp_id);

    } else if (protocol == IPPROTO_UDP) {
        if (length - (payload - pkt) < sizeof(struct udphdr)) {
            return;
        }

        struct udphdr *udp = (struct udphdr *) payload;
        sport = ntohs(udp->source);
        dport = ntohs(udp->dest);

    } else if (protocol == IPPROTO_TCP) {
        if (length - (payload - pkt) < sizeof(struct tcphdr)) {
            return;
        }

        struct tcphdr *tcp = (struct tcphdr *) payload;
        sport = ntohs(tcp->source);
        dport = ntohs(tcp->dest);

        if (tcp->syn) {
            syn = 1;
            flags[flen++] = 'S';
        }
        if (tcp->ack)
            flags[flen++] = 'A';
        if (tcp->psh)
            flags[flen++] = 'P';
        if (tcp->fin)
            flags[flen++] = 'F';
        if (tcp->rst)
            flags[flen++] = 'R';
    } else if (protocol != IPPROTO_HOPOPTS && protocol != IPPROTO_IGMP && protocol != IPPROTO_ESP) {
        return;
    }

    flags[flen] = 0;

    if (sessions >= maxsessions) {
        if ((protocol == IPPROTO_ICMP || protocol == IPPROTO_ICMPV6) ||
            (protocol == IPPROTO_UDP && !has_udp_session(args, pkt, payload)) ||
            (protocol == IPPROTO_TCP && syn)) {
            return;
        }
    }

    jint uid = -1;
    char server_name[TLS_SNI_LENGTH + 1];
    *server_name = 0;

    if (protocol == IPPROTO_TCP) {
        const struct tcphdr *tcphdr = (struct tcphdr *) payload;
        const uint8_t tcpoptlen = (uint8_t) ((tcphdr->doff - 5) * 4);
        const uint8_t *data = payload + sizeof(struct tcphdr) + tcpoptlen;
        const uint16_t datalen = (const uint16_t) (length - (data - pkt));
    }

    if (*server_name != 0)
        strcpy(data, "sni");

    if (protocol == IPPROTO_ICMP || protocol == IPPROTO_ICMPV6 ||
        (protocol == IPPROTO_UDP && !has_udp_session(args, pkt, payload)) ||
        (protocol == IPPROTO_TCP && syn)) {
    }

    int allowed = 1;
    struct allowed *redirect = NULL;

    if (protocol == IPPROTO_UDP && has_udp_session(args, pkt, payload))
        allowed = 1;
    else if (protocol == IPPROTO_TCP && (!syn || (uid == 0 && dport == 53)) && *server_name == 0)
        allowed = 1;

    // Apply packet filtering
    jboolean allow_packet = JNI_TRUE;
    
    if (protocol == IPPROTO_ICMP || protocol == IPPROTO_ICMPV6) {
        allow_packet = filter_icmp_packet(args, pkt, length, "TUN_IN");
        if (allow_packet) {
            handle_icmp(args, pkt, length, payload, uid, epoll_fd);
        }
    } else if (protocol == IPPROTO_UDP) {
        allow_packet = filter_udp_packet(args, pkt, length, "TUN_IN");
        if (allow_packet) {
            handle_udp(args, pkt, length, payload, uid, redirect, epoll_fd);
        }
    } else if (protocol == IPPROTO_TCP) {
        jint verdict = filter_tcp_packet(args, pkt, length, "TUN_IN");
        allow_packet = (verdict != TCP_FILTER_DROP);
        if (allow_packet) {
            // The owner of a new connection, so a network change can reset the connections of apps blocked on the
            // new network (reset_uid_sessions)
            if (syn && verdict >= 0)
                uid = verdict;
            handle_tcp(args, pkt, length, payload, uid, allowed, redirect, epoll_fd);
        }
    }
}