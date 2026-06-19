package stack

import "sync"

// maxBufSize is the maximum byte slice returned by the pool.
// WritePacket prepends IPv4 + UDP headers (28 bytes) to a payload that may be
// up to 65535 bytes (the maximum single Read from an upstream net.Conn), so
// the buffer must be at least 65535 + 28 = 65563 bytes.
const maxBufSize = 65535 + IPv4HeaderLen + UDPHeaderLen // 65563

var bufferPool = sync.Pool{New: func() any { return make([]byte, maxBufSize) }}

func NewBuffer() []byte   { return bufferPool.Get().([]byte) }
func FreeBuffer(b []byte) { bufferPool.Put(b) }
