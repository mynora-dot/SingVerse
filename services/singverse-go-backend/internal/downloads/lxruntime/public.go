package lxruntime

import (
	"net/http"
	"net/url"
)

// PublicTransport pins DNS results and disables proxy discovery. Callers own
// redirect validation and the streaming request deadline.
func PublicTransport() *http.Transport { return newNetwork().client.Transport.(*http.Transport) }

// CheckPublicURL validates the complete URL before any outbound connection.
func CheckPublicURL(value string, httpsOnly bool) (*url.URL, error) {
	return checkedURL(value, httpsOnly)
}
