package libgost

import (
	"bytes"
	"encoding/binary"
	"io"
	"net"
	"testing"
	"time"
)

// tlsClientHello builds a minimal valid TLS 1.2 ClientHello record carrying
// the given SNI, matching the format the tls-dissector understands.
func tlsClientHello(sni string) []byte {
	name := []byte(sni)

	// SNI extension (type 0 = host_name)
	sniBody := new(bytes.Buffer)
	binary.Write(sniBody, binary.BigEndian, uint16(len(name)+3)) // server_name_list length
	sniBody.WriteByte(0)                                         // name_type
	binary.Write(sniBody, binary.BigEndian, uint16(len(name)))   // name length
	sniBody.Write(name)

	extBody := new(bytes.Buffer)
	binary.Write(extBody, binary.BigEndian, uint16(0)) // extension type: server_name
	binary.Write(extBody, binary.BigEndian, uint16(sniBody.Len()))
	extBody.Write(sniBody.Bytes())

	// ClientHello handshake body
	body := new(bytes.Buffer)
	binary.Write(body, binary.BigEndian, uint16(0x0303)) // client_version TLS 1.2
	body.Write(make([]byte, 32))                         // random
	body.WriteByte(0)                                    // session_id length
	binary.Write(body, binary.BigEndian, uint16(2))      // cipher_suites length
	binary.Write(body, binary.BigEndian, uint16(0xc02b)) // TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
	body.WriteByte(1)                                    // compression methods length
	body.WriteByte(0)
	binary.Write(body, binary.BigEndian, uint16(extBody.Len()))
	body.Write(extBody.Bytes())

	// Handshake header: type ClientHello(1) + 3-byte length
	hs := new(bytes.Buffer)
	hs.WriteByte(0x01)
	l := body.Len()
	hs.Write([]byte{byte(l >> 16), byte(l >> 8), byte(l)})
	hs.Write(body.Bytes())

	// TLS record: type Handshake(0x16), version 0x0301, length
	rec := new(bytes.Buffer)
	rec.WriteByte(0x16)
	rec.Write([]byte{0x03, 0x01})
	binary.Write(rec, binary.BigEndian, uint16(hs.Len()))
	rec.Write(hs.Bytes())
	return rec.Bytes()
}

func TestSniffTCPDomainTLS(t *testing.T) {
	data := tlsClientHello("example.com")
	if d := sniffTCPDomain(data); d != "example.com" {
		t.Fatalf("sniffed %q, want example.com", d)
	}
}

func TestSniffTCPDomainHTTP(t *testing.T) {
	data := []byte("GET /index.html HTTP/1.1\r\nHost: example.com\r\nUser-Agent: curl/8.0\r\n\r\n")
	if d := sniffTCPDomain(data); d != "example.com" {
		t.Fatalf("sniffed %q, want example.com", d)
	}
}

func TestSniffTCPDomainHTTPNoHost(t *testing.T) {
	data := []byte("GET / HTTP/1.0\r\n\r\n")
	if d := sniffTCPDomain(data); d != "" {
		t.Fatalf("sniffed %q, want empty", d)
	}
}

func TestSniffTCPDomainUnknown(t *testing.T) {
	data := []byte("SSH-2.0-OpenSSH_9.0\r\n")
	if d := sniffTCPDomain(data); d != "" {
		t.Fatalf("sniffed %q, want empty", d)
	}
}

func TestSniffTCPDomainEmpty(t *testing.T) {
	if d := sniffTCPDomain(nil); d != "" {
		t.Fatalf("sniffed %q, want empty", d)
	}
}

func TestSniffTCPDomainConnTLS(t *testing.T) {
	server, client := net.Pipe()
	defer server.Close()

	data := tlsClientHello("example.com")
	go func() {
		client.Write(data)
		client.Write([]byte("REMAINDER"))
		client.Close()
	}()

	domain, extra, sniffed := sniffTCPDomainConn(server, 2*time.Second)
	if !sniffed {
		t.Fatal("expected sniff to succeed")
	}
	if domain != "example.com" {
		t.Fatalf("domain = %q, want example.com", domain)
	}
	if string(extra) != string(data) {
		t.Fatalf("consumed bytes mismatch: got %d bytes, want %d", len(extra), len(data))
	}
	// The stream must remain readable after the consumed prefix.
	rest, err := io.ReadAll(server)
	if err != nil {
		t.Fatalf("read remainder: %v", err)
	}
	if string(rest) != "REMAINDER" {
		t.Fatalf("remainder = %q, want REMAINDER", rest)
	}
}

func TestSniffTCPDomainConnHTTP(t *testing.T) {
	server, client := net.Pipe()
	defer server.Close()

	data := []byte("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\nBODY")
	go func() {
		client.Write(data)
		client.Close()
	}()

	domain, extra, sniffed := sniffTCPDomainConn(server, 2*time.Second)
	if !sniffed {
		t.Fatal("expected sniff to succeed")
	}
	if domain != "example.com" {
		t.Fatalf("domain = %q, want example.com", domain)
	}
	if string(extra) != string(data[:len(data)-len("BODY")]) {
		t.Fatalf("consumed bytes mismatch")
	}
}

func TestSniffTCPDomainConnUnknown(t *testing.T) {
	server, client := net.Pipe()
	defer server.Close()

	// SSH banner: not TLS, not HTTP. Must return quickly, consume only the
	// banner, and not mangle the stream.
	go func() {
		client.Write([]byte("SSH-2.0-OpenSSH_9.0\r\nREST"))
		client.Close()
	}()

	domain, extra, sniffed := sniffTCPDomainConn(server, 500*time.Millisecond)
	if sniffed {
		t.Fatalf("unexpected sniff success: %q", domain)
	}
	if domain != "" {
		t.Fatalf("domain = %q, want empty", domain)
	}
	rest, _ := io.ReadAll(server)
	if string(extra)+string(rest) != "SSH-2.0-OpenSSH_9.0\r\nREST" {
		t.Fatalf("stream mangled: extra=%q rest=%q", extra, rest)
	}
}

func TestSniffTCPDomainConnIdleTimeout(t *testing.T) {
	server, client := net.Pipe()
	defer server.Close()
	_ = client // no data written

	start := time.Now()
	domain, extra, sniffed := sniffTCPDomainConn(server, 100*time.Millisecond)
	if sniffed {
		t.Fatal("sniff should not succeed on idle connection")
	}
	if domain != "" || len(extra) != 0 {
		t.Fatalf("unexpected result: domain=%q extra=%d", domain, len(extra))
	}
	if time.Since(start) > time.Second {
		t.Fatalf("sniff took %v, want ~100ms timeout", time.Since(start))
	}
}

func TestSniffConnSplicesPrefix(t *testing.T) {
	server, client := net.Pipe()
	defer server.Close()

	data := tlsClientHello("example.com")
	go func() {
		client.Write(data)
		client.Write([]byte("REST"))
		client.Close()
	}()

	h := &singTunHandler{}
	domain, wrapped := h.sniffConn(server)
	if domain != "example.com" {
		t.Fatalf("domain = %q, want example.com", domain)
	}
	// The wrapped conn must yield the sniffed record followed by the rest.
	all, err := io.ReadAll(wrapped)
	if err != nil {
		t.Fatalf("read wrapped: %v", err)
	}
	want := string(data) + "REST"
	if string(all) != want {
		t.Fatalf("wrapped stream mangled: got %d bytes, want %d", len(all), len(want))
	}
}

func TestSniffConnUnknownSplices(t *testing.T) {
	server, client := net.Pipe()
	defer server.Close()

	go func() {
		client.Write([]byte("SSH-2.0-OpenSSH_9.0\r\nREST"))
		client.Close()
	}()

	h := &singTunHandler{}
	domain, wrapped := h.sniffConn(server)
	if domain != "" {
		t.Fatalf("domain = %q, want empty", domain)
	}
	all, _ := io.ReadAll(wrapped)
	if string(all) != "SSH-2.0-OpenSSH_9.0\r\nREST" {
		t.Fatalf("unknown protocol stream mangled: %q", all)
	}
}
