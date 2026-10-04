package main
import "os"
func main() { if os.Getenv("NETGUARD_CARRIER") == "wbstream" { wbstreamMain() } else { telemostMain() } }
