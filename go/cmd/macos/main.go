// go/cmd/macos/main.go
package main

/*
#include <stdlib.h>
struct gost_info {
    char* status_json;
};
*/
import "C"

import (
	"os"
	"unsafe"

	"libgost/gostlib"
)

//export gostRunYaml
func gostRunYaml(yaml *C.char, fd *C.long) C.int {
	// The fd is kept for future log-pipe use; gost v3 logs internally.
	_ = os.NewFile(uintptr(*fd), "logpipe")

	if err := gostlib.Start(C.GoString(yaml)); err != nil {
		return 1
	}
	return 0
}

//export gostStop
func gostStop() C.int {
	gostlib.StopGost()
	return 0
}

//export gostInfo
func gostInfo() *C.struct_gost_info {
	info := (*C.struct_gost_info)(C.malloc(C.size_t(unsafe.Sizeof(C.struct_gost_info{}))))
	info.status_json = C.CString(gostlib.GetStatus())
	return info
}

func main() {}
