package libgost

import (
	"bytes"
	"io"
	"net"
	"strings"
	"time"

	dissector "github.com/go-gost/tls-dissector"
)

// sniffTCPDomain recovers a domain name from the leading bytes of a TCP
// stream. It recognizes:
//
//   - TLS ClientHello → SNI (server_name extension)
//   - HTTP request headers → Host header
//
// It is a pure function over the buffered bytes: the caller is responsible
// for accumulating enough data (the full TLS record, or the HTTP headers up
// to the blank line). Returns "" when the stream is not recognized.
func sniffTCPDomain(data []byte) string {
	if len(data) < 3 {
		return ""
	}
	// TLS record: content type 0x16 (handshake), protocol version 0x03xx.
	if data[0] == 0x16 && data[1] == 0x03 {
		info, err := dissector.ParseClientHello(bytes.NewReader(data))
		if err != nil {
			return ""
		}
		return info.ServerName
	}
	return sniffHTTPHost(data)
}

// sniffHTTPHost extracts the Host header from HTTP request header bytes.
// It scans header lines after the request line; the first Host wins.
func sniffHTTPHost(data []byte) string {
	lines := strings.Split(string(data), "\r\n")
	for _, line := range lines[1:] {
		key, value, ok := strings.Cut(line, ":")
		if ok && strings.EqualFold(strings.TrimSpace(key), "host") {
			return strings.TrimSpace(value)
		}
	}
	return ""
}

const (
	// maxSniffRecord bounds the TLS record we are willing to buffer for
	// ClientHello parsing (max legal TLS record is 2^14+2048 bytes).
	maxSniffRecord = 64 * 1024
	// maxHTTPHeader bounds buffered HTTP header bytes while sniffing.
	maxHTTPHeader = 16 * 1024
)

// sniffTCPDomainConn recovers a domain from the leading bytes of conn without
// losing them: it returns the domain (when recognized) plus the exact bytes
// already consumed, so the caller can splice them back with io.MultiReader and
// forward the connection unchanged.
//
// A short read deadline (timeout) prevents non-TLS/HTTP streams (e.g. SSH
// banners) from blocking the sniff: on timeout or unrecognized traffic it
// returns ("", consumedBytes, false).
func sniffTCPDomainConn(conn net.Conn, timeout time.Duration) (domain string, extra []byte, sniffed bool) {
	conn.SetReadDeadline(time.Now().Add(timeout))
	defer conn.SetReadDeadline(time.Time{})

	var hdr [5]byte
	n, err := io.ReadFull(conn, hdr[:])
	if err != nil {
		// Idle connection or short read: hand back whatever we consumed.
		if n > 0 {
			return "", append([]byte(nil), hdr[:n]...), false
		}
		return "", nil, false
	}

	// TLS record: content type 0x16 (handshake), version 0x03xx.
	if hdr[0] == 0x16 && hdr[1] == 0x03 {
		recLen := int(hdr[3])<<8 | int(hdr[4])
		if recLen > maxSniffRecord {
			return "", append([]byte(nil), hdr[:]...), false
		}
		buf := make([]byte, 5+recLen)
		copy(buf, hdr[:])
		if _, err := io.ReadFull(conn, buf[5:]); err != nil {
			return "", buf, false
		}
		return sniffTCPDomain(buf), buf, true
	}

	// HTTP: read the request line, then headers up to the blank line.
	// Bytes are read one at a time (no read-ahead) so the connection stream
	// position stays exactly at the end of the consumed prefix — the caller
	// splices `consumed` back and everything after it must still be readable.
	// Stop early if the first line is not an HTTP request, so unknown
	// protocols (SSH, ...) return after a single line instead of timing out.
	consumed := append([]byte(nil), hdr[:]...)
	firstLine, err := readUntilNewline(conn, maxHTTPHeader)
	if err != nil {
		return "", consumed, false
	}
	consumed = append(consumed, firstLine...)
	fullFirstLine := string(hdr[:]) + string(firstLine)
	if !looksLikeHTTPRequestLine(fullFirstLine) {
		return sniffHTTPHost(consumed), consumed, false
	}
	for len(consumed) < maxHTTPHeader {
		line, err := readUntilNewline(conn, maxHTTPHeader)
		if err != nil {
			break
		}
		consumed = append(consumed, line...)
		if string(line) == "\r\n" || string(line) == "\n" {
			return sniffHTTPHost(consumed), consumed, true
		}
	}
	return sniffHTTPHost(consumed), consumed, false
}

// readUntilNewline reads bytes from conn one at a time until a '\n' is seen
// or max bytes have been read. Reading byte-by-byte (instead of buffered IO)
// guarantees no extra bytes are consumed from conn beyond the returned slice.
func readUntilNewline(conn net.Conn, max int) ([]byte, error) {
	buf := make([]byte, 0, 64)
	var one [1]byte
	for len(buf) < max {
		if _, err := io.ReadFull(conn, one[:]); err != nil {
			return buf, err
		}
		buf = append(buf, one[0])
		if one[0] == '\n' {
			return buf, nil
		}
	}
	return buf, nil
}

// looksLikeHTTPRequestLine reports whether line looks like an HTTP request
// line (method + target + version) or the HTTP/2 preface.
func looksLikeHTTPRequestLine(line string) bool {
	methods := []string{"GET ", "POST ", "PUT ", "DELETE ", "HEAD ", "OPTIONS ", "PATCH ", "CONNECT ", "TRACE "}
	for _, m := range methods {
		if strings.HasPrefix(line, m) {
			return true
		}
	}
	return strings.HasPrefix(line, "PRI *")
}

// sniffTimeout bounds the per-connection sniff read. TLS ClientHellos and
// HTTP headers arrive immediately, so only silent peers hit the timeout.
const sniffTimeout = 500 * time.Millisecond

// multiReadConn reads from an io.Reader (the sniffed prefix spliced with the
// live connection) while keeping all other net.Conn behavior from the
// underlying connection. Writes go to the underlying conn untouched.
type multiReadConn struct {
	net.Conn
	r io.Reader
}

func (c *multiReadConn) Read(b []byte) (int, error) { return c.r.Read(b) }

// sniffConn recovers a domain from conn's leading bytes when possible and
// returns a replacement conn that replays every byte consumed by the sniff
// (so no data is lost, whether or not a domain was found).
func (h *singTunHandler) sniffConn(conn net.Conn) (domain string, wrapped net.Conn) {
	d, extra, _ := sniffTCPDomainConn(conn, sniffTimeout)
	if len(extra) > 0 {
		wrapped = &multiReadConn{Conn: conn, r: io.MultiReader(bytes.NewReader(extra), conn)}
	} else {
		wrapped = conn
	}
	return d, wrapped
}
