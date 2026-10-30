package com.mynora.singverse.tv;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Map;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

/** Opt-in LAN advertisement. In bridge Docker, publish from the host instead. */
@Component
public class TvDiscovery {
    private final String address;
    private final int port;
    private JmDNS publisher;

    public TvDiscovery(
            @Value("${singverse.tv.discovery-address:}") String address,
            @Value("${singverse.tv.discovery-port:18080}") int port) {
        this.address = address;
        this.port = port;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (address.isBlank()) return;
        try {
            var bind = InetAddress.getByName(address);
            if ((!bind.isSiteLocalAddress() && !bind.isLoopbackAddress())
                    || port < 1
                    || port > 65535) throw new IllegalArgumentException();
            publisher = JmDNS.create(bind);
            publisher.registerService(
                    ServiceInfo.create(
                            "_singverse._tcp.local.",
                            "SingVerse NAS",
                            port,
                            0,
                            0,
                            Map.of("api", "1", "scheme", "http")));
        } catch (Exception failure) {
            close();
            org.slf4j.LoggerFactory.getLogger(TvDiscovery.class)
                    .warn("TV LAN discovery unavailable; manual NAS address remains available");
        }
    }

    @PreDestroy
    public synchronized void close() {
        if (publisher != null) {
            try {
                publisher.close();
            } catch (Exception ignored) {
            }
            publisher = null;
        }
    }
}
