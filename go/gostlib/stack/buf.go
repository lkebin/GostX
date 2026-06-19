package stack

import "sync"

var bufferPool = sync.Pool{New: func() any { return make([]byte, 65535) }}

func NewBuffer() []byte  { return bufferPool.Get().([]byte) }
func FreeBuffer(b []byte) { bufferPool.Put(b) }
