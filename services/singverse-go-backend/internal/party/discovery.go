package party

import (
	"errors"
	"net"

	"github.com/grandcat/zeroconf"
)

// StartDiscovery is opt-in. Bridge-network deployments normally advertise from
// the NAS host and leave the backend discovery address empty.
func (s *Service) StartDiscovery() {
	s.discoveryMu.Lock()
	defer s.discoveryMu.Unlock()
	if s.cfg.TVDiscoveryAddress == "" || s.discovery != nil {
		return
	}
	if err := s.advertise(); err != nil {
		s.log.Warn("TV LAN discovery unavailable; manual NAS address remains available")
	}
}
func (s *Service) advertise() error {
	ip := net.ParseIP(s.cfg.TVDiscoveryAddress)
	if ip == nil || (!ip.IsPrivate() && !ip.IsLoopback()) || s.cfg.TVDiscoveryPort < 1 || s.cfg.TVDiscoveryPort > 65535 {
		return errors.New("invalid discovery interface or port")
	}
	interfaces, err := net.Interfaces()
	if err != nil {
		return err
	}
	var selected []net.Interface
	for _, iface := range interfaces {
		addresses, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, address := range addresses {
			candidate, _, err := net.ParseCIDR(address.String())
			if err == nil && candidate.Equal(ip) {
				selected = append(selected, iface)
				break
			}
		}
	}
	if len(selected) == 0 {
		return errors.New("discovery address is not bound to a local interface")
	}
	host := "singverse-" + hash(ip.String())[:12] + ".local."
	publisher, err := zeroconf.RegisterProxy("SingVerse NAS", "_singverse._tcp", "local.", s.cfg.TVDiscoveryPort, host, []string{ip.String()}, []string{"api=1", "scheme=http"}, selected)
	if err != nil {
		return err
	}
	s.discovery = publisher
	return nil
}
func (s *Service) closeDiscovery() {
	s.discoveryMu.Lock()
	defer s.discoveryMu.Unlock()
	if s.discovery != nil {
		s.discovery.Shutdown()
		s.discovery = nil
	}
}
