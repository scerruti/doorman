import pyshark
import sys

def analyze_btsnoop(file_path):
    print(f"Scanning '{file_path}' for SwitchBot BLE GATT writes...")
    
    # Filter for GATT Write Requests (0x12) and Write Commands (0x52)
    # This filters out all the background noise and only looks at actionable commands
    display_filter = 'btatt.opcode in {0x12, 0x52}'
    
    try:
        cap = pyshark.FileCapture(file_path, display_filter=display_filter)
        
        found_commands = 0
        for pkt in cap:
            try:
                if hasattr(pkt, 'btatt'):
                    handle = pkt.btatt.handle
                    value_hex = pkt.btatt.value.replace(':', '')
                    
                    found_commands += 1
                    print(f"---")
                    print(f"Packet Number: {pkt.number}")
                    print(f"GATT Handle:   {handle}")
                    print(f"Payload (Hex): {value_hex}")
                    
                    if value_hex == "570100":
                        print("Analysis:      Unencrypted OPEN/TOGGLE command detected.")
                    elif value_hex.startswith('57') and len(value_hex) > 6:
                        print("Analysis:      Encrypted command detected (likely containing password hash).")
            except AttributeError:
                continue
                
        cap.close()
        
        if found_commands == 0:
            print("No SwitchBot commands found in this log.")
            
    except Exception as e:
        print(f"Error reading file: {e}")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 parse_snoop.py <path_to_btsnooz_hci.log>")
    else:
        analyze_btsnoop(sys.argv[1])