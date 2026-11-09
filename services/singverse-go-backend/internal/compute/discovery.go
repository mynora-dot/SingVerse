package compute

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

type Scan struct {
	Network string `json:"network"`
	Port    *int   `json:"port"`
}
type Pair struct {
	DiscoveryID string `json:"discoveryId"`
	Revision    int64  `json:"revision"`
}
type Item struct {
	ID               string `json:"id"`
	Name             string `json:"name"`
	Endpoint         string `json:"endpoint"`
	NodeID           string `json:"nodeId"`
	PairingAvailable bool   `json:"pairingAvailable"`
	Message          string `json:"message"`
}
type Result struct {
	Items   []Item `json:"items"`
	Message string `json:"message"`
}
type advertisement struct {
	nodeID, name, owner string
	enabled             bool
}

var uuidPattern = regexp.MustCompile(`^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$`)
var hex64Pattern = regexp.MustCompile(`^[a-f0-9]{64}$`)

func advertised(v map[string]any) (advertisement, error) {
	a := advertisement{nodeID: text(v["nodeId"]), name: text(v["name"]), owner: text(v["ownerId"])}
	var ok bool
	a.enabled, ok = v["pairingEnabled"].(bool)
	if text(v["service"]) != "singverse-compute" || text(v["apiVersion"]) != "1" || !uuidPattern.MatchString(a.nodeID) || strings.TrimSpace(a.name) == "" || len(a.name) > 128 || hasControl(a.name) || (a.owner != "" && !hex64Pattern.MatchString(a.owner)) || !ok {
		return advertisement{}, errors.New("invalid compute advertisement")
	}
	return a, nil
}
func available(a advertisement, client string) bool {
	return a.enabled && (a.owner == "" || a.owner == client)
}
func exchange(ctx context.Context, endpoint, path string, payload any, timeout time.Duration) (map[string]any, error) {
	if err := requirePrivate(endpoint); err != nil {
		return nil, err
	}
	status, b, err := request(ctx, endpoint, path, "", payload, timeout, 8192)
	if err != nil {
		return nil, err
	}
	if status != 200 {
		return nil, reject(502, "节点未接受配对请求，请检查配对开关后重新扫描")
	}
	return jsonObject(b)
}
func (s *Service) purgeLocked() {
	now := time.Now()
	for id, c := range s.candidates {
		if now.After(c.expires) {
			delete(s.candidates, id)
		}
	}
	if len(s.candidates) > 256 {
		clear(s.candidates)
	}
}
func (s *Service) inspect(ctx context.Context, endpoint, client string) *Item {
	v, err := exchange(ctx, endpoint, "/internal/v1/lan/discovery", nil, 500*time.Millisecond)
	if err != nil {
		return nil
	}
	a, err := advertised(v)
	if err != nil {
		return nil
	}
	id, err := randomID()
	if err != nil {
		return nil
	}
	s.mu.Lock()
	s.purgeLocked()
	s.candidates[id] = candidate{endpoint, a.nodeID, time.Now().Add(2 * time.Minute)}
	s.mu.Unlock()
	allowed := available(a, client)
	message := "节点已由其他业务服务管理"
	if allowed {
		if a.owner == "" {
			message = "可配对，无需填写连接密钥"
		} else {
			message = "已属于本业务服务，可恢复连接"
		}
	} else if !a.enabled {
		message = "节点未开放局域网配对"
	}
	return &Item{id, a.name, endpoint, a.nodeID, allowed, message}
}
func (s *Service) Discover(ctx context.Context, input Scan) (Result, error) {
	if !s.scanning.CompareAndSwap(false, true) {
		return Result{}, reject(429, "正在扫描局域网，请稍后重试")
	}
	defer s.scanning.Store(false)
	ctx, cancel := context.WithTimeout(ctx, 9500*time.Millisecond)
	defer cancel()
	stop := context.AfterFunc(s.root, cancel)
	defer stop()
	ports := []int{18090, 8090}
	if input.Port != nil {
		if *input.Port < 1 || *input.Port > 65535 {
			return Result{}, reject(400, "节点端口应为1～65535")
		}
		ports = []int{*input.Port}
	}
	targets, err := s.targets(ctx, input.Network, ports, input.Port != nil)
	if err != nil {
		return Result{}, err
	}
	type identityResult struct {
		id  string
		err error
	}
	identityReady := make(chan identityResult, 1)
	go func() { id, e := s.clientID(ctx); identityReady <- identityResult{id, e} }()
	var client string
	select {
	case <-ctx.Done():
		return Result{}, reject(503, "扫描准备超时，请重试")
	case value := <-identityReady:
		if value.err != nil {
			return Result{}, reject(503, "节点配对身份暂不可用，请检查服务密钥配置")
		}
		client = value.id
	}
	jobs := make(chan string)
	results := make(chan *Item, 32)
	var workers sync.WaitGroup
	for i := 0; i < 32; i++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for {
				select {
				case <-ctx.Done():
					return
				case endpoint, ok := <-jobs:
					if !ok {
						return
					}
					item := s.inspect(ctx, endpoint, client)
					select {
					case results <- item:
					case <-ctx.Done():
						return
					}
				}
			}
		}()
	}
	go func() {
		defer close(jobs)
		for _, endpoint := range targets {
			select {
			case jobs <- endpoint:
			case <-ctx.Done():
				return
			}
		}
	}()
	go func() { workers.Wait(); close(results) }()
	items := make([]Item, 0, 32)
collect:
	for {
		select {
		case <-ctx.Done():
			break collect
		case item, ok := <-results:
			if !ok {
				break collect
			}
			if item != nil {
				items = append(items, *item)
				if len(items) >= 32 {
					break collect
				}
			}
		}
	}
	cancel()
	workers.Wait()
	sort.Slice(items, func(i, j int) bool { return items[i].Endpoint < items[j].Endpoint })
	message := "未发现计算节点；可指定业务机器可达的局域网网段后重试，并检查节点端口与配对开关"
	if len(items) > 0 {
		message = fmt.Sprintf("发现 %d 个计算节点，选择节点后将检查认证与业务回源", len(items))
	}
	return Result{items, message}, nil
}
func (s *Service) Pair(ctx context.Context, input Pair) (Settings, error) {
	if input.DiscoveryID == "" || len(input.DiscoveryID) > 64 || input.Revision < 0 {
		return Settings{}, reject(400, "请选择刚刚发现的计算节点")
	}
	previous, err := s.View(ctx)
	if err != nil {
		return Settings{}, err
	}
	if previous.Revision != input.Revision {
		return Settings{}, reject(409, "配置已被修改，请刷新后重试")
	}
	s.mu.Lock()
	s.purgeLocked()
	candidate, ok := s.candidates[input.DiscoveryID]
	s.mu.Unlock()
	if !ok {
		return Settings{}, reject(404, "发现结果已过期，请重新扫描")
	}
	if err = requirePrivate(candidate.endpoint); err != nil {
		return Settings{}, err
	}
	result, err := s.pair(ctx, candidate, previous, input.Revision)
	if err == nil {
		return result, nil
	}
	var f *failure
	if errors.As(err, &f) {
		return Settings{}, err
	}
	if ctx.Err() != nil {
		return Settings{}, reject(503, "节点配对已中断，请重试")
	}
	return Settings{}, reject(502, "节点配对失败或超时，请检查节点配对开关后重新扫描")
}
func (s *Service) pair(ctx context.Context, c candidate, previous Settings, revision int64) (Settings, error) {
	client, err := s.clientID(ctx)
	if err != nil {
		return Settings{}, err
	}
	v, err := exchange(ctx, c.endpoint, "/internal/v1/lan/discovery", nil, 1500*time.Millisecond)
	if err != nil {
		return Settings{}, err
	}
	a, err := advertised(v)
	if err != nil {
		return Settings{}, err
	}
	if a.nodeID != c.nodeID {
		return Settings{}, reject(409, "节点身份已变化，请重新扫描")
	}
	if !available(a, client) {
		return Settings{}, reject(409, "节点尚未开放配对，或已由其他业务服务管理")
	}
	v, err = exchange(ctx, c.endpoint, "/internal/v1/lan/challenge", map[string]string{"nodeId": c.nodeID, "clientId": client}, 4*time.Second)
	if err != nil {
		return Settings{}, err
	}
	nonce := text(v["challenge"])
	if text(v["nodeId"]) != c.nodeID || !hex64Pattern.MatchString(nonce) {
		return Settings{}, reject(502, "节点配对挑战无效，请重新扫描")
	}
	public, signature, err := s.proof(ctx, c.nodeID, nonce)
	if err != nil {
		return Settings{}, err
	}
	v, err = exchange(ctx, c.endpoint, "/internal/v1/lan/pair", map[string]string{"nodeId": c.nodeID, "clientId": client, "publicKey": public, "challenge": nonce, "signature": signature}, 4*time.Second)
	if err != nil {
		return Settings{}, err
	}
	encrypted, ok := v["encryptedToken"].(string)
	if text(v["nodeId"]) != c.nodeID || text(v["challenge"]) != nonce || !ok || len(encrypted) > 1024 {
		return Settings{}, reject(502, "节点配对响应无效，请重新扫描")
	}
	token, err := s.decryptPairingToken(ctx, c.nodeID, nonce, encrypted)
	if err != nil {
		return Settings{}, err
	}
	check, err := s.Probe(ctx, &Connection{c.endpoint, token, previous.Mappings})
	if err != nil {
		return Settings{}, err
	}
	if check.Status != "AVAILABLE" {
		return Settings{}, reject(422, check.Message)
	}
	return s.Save(ctx, Save{Mode: "MANUAL", Endpoint: c.endpoint, Token: token, Mappings: previous.Mappings, Revision: revision})
}

func subnet(value string) ([]netip.Addr, error) {
	invalid := reject(400, "扫描网段仅支持私有IPv4 /24～/32，或本机回环地址 /32")
	if len(value) > 32 {
		return nil, invalid
	}
	p, err := netip.ParsePrefix(value)
	if err != nil || !p.Addr().Is4() || p.Bits() < 24 || p.Bits() > 32 || (!p.Addr().IsPrivate() && !(p.Addr().IsLoopback() && p.Bits() == 32)) {
		return nil, invalid
	}
	count := 1 << (32 - p.Bits())
	items := make([]netip.Addr, 0, count)
	ip := p.Masked().Addr()
	for i := 0; i < count; i++ {
		items = append(items, ip)
		ip = ip.Next()
	}
	return items, nil
}
func nodeEndpoint(scheme string, ip netip.Addr, port int) string {
	return scheme + "://" + net.JoinHostPort(ip.String(), strconv.Itoa(port))
}
func (s *Service) targets(ctx context.Context, network string, ports []int, explicit bool) ([]string, error) {
	result := []string{}
	seen := map[string]bool{}
	add := func(v string) {
		if !seen[v] {
			seen[v] = true
			result = append(result, v)
		}
	}
	addAddress := func(ip netip.Addr) {
		for _, port := range ports {
			add(nodeEndpoint("http", ip, port))
		}
	}
	if strings.TrimSpace(network) != "" {
		addresses, err := subnet(network)
		if err != nil {
			return nil, err
		}
		for _, ip := range addresses {
			addAddress(ip)
		}
		return result, nil
	}
	networks := []string{}
	seenNetworks := map[string]bool{}
	addNetwork := func(ip netip.Addr) {
		if ip.Is4() && ip.IsPrivate() {
			prefix := netip.PrefixFrom(ip, 24).Masked().String()
			if !seenNetworks[prefix] {
				seenNetworks[prefix] = true
				networks = append(networks, prefix)
			}
		}
	}
	addOrigin := func(value string, callback bool) {
		if value == "" {
			return
		}
		canonical, err := origin(value)
		if err != nil {
			return
		}
		u, _ := url.Parse(canonical)
		resolveCtx, cancel := context.WithTimeout(ctx, 400*time.Millisecond)
		defer cancel()
		addresses, err := net.DefaultResolver.LookupNetIP(resolveCtx, "ip", u.Hostname())
		if err != nil {
			return
		}
		for _, ip := range addresses {
			ip = ip.Unmap()
			if !privateAddress(ip) {
				continue
			}
			addNetwork(ip)
			if callback || explicit {
				addAddress(ip)
			} else {
				port := 80
				if u.Scheme == "https" {
					port = 443
				}
				if u.Port() != "" {
					port, _ = parsePort(u.Port())
				}
				add(nodeEndpoint(u.Scheme, ip, port))
			}
		}
	}
	if callback, err := s.CallbackOrigin(nil); err == nil {
		addOrigin(callback, true)
	}
	current, err := s.View(ctx)
	if err != nil {
		return nil, err
	}
	addOrigin(current.Endpoint, false)
	addOrigin(current.AutomaticEndpoint, false)
	interfaces, _ := net.Interfaces()
	for _, nic := range interfaces {
		if nic.Flags&net.FlagUp == 0 || nic.Flags&net.FlagLoopback != 0 {
			continue
		}
		addresses, _ := nic.Addrs()
		for _, address := range addresses {
			if prefix, err := netip.ParsePrefix(address.String()); err == nil {
				addNetwork(prefix.Addr().Unmap())
			}
		}
	}
	for i, network := range networks {
		if i >= 3 {
			break
		}
		addresses, _ := subnet(network)
		for _, ip := range addresses {
			addAddress(ip)
		}
	}
	hosts := map[string]bool{}
	bounded := make([]string, 0, len(result))
	for _, endpoint := range result {
		u, _ := url.Parse(endpoint)
		host := u.Hostname()
		if !hosts[host] && len(hosts) >= 768 {
			continue
		}
		hosts[host] = true
		bounded = append(bounded, endpoint)
	}
	return bounded, nil
}
