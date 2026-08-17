import serial
import time
import sys

port = "COM20"
baudrate = 19200

try:
    ser = serial.Serial(port, baudrate, timeout=1)
    print(f"Successfully opened {port} at {baudrate} baud.")
    print("Listening for incoming data... Please try to trigger the sensor now!")
    print("-" * 50)
    sys.stdout.flush()
except Exception as e:
    print(f"Failed to open port {port}: {e}")
    sys.exit(1)

while True:
    try:
        if ser.in_waiting > 0:
            data = ser.read(ser.in_waiting)
            # Print hex representation
            hex_data = data.hex()
            # Split into chunks of 2 characters (bytes) for readability
            formatted_hex = ' '.join(hex_data[i:i+2] for i in range(0, len(hex_data), 2)).upper()
            print(f"[{time.strftime('%H:%M:%S')}] RECV: {formatted_hex}")
            sys.stdout.flush()
        time.sleep(0.1)
    except KeyboardInterrupt:
        print("\nStopping...")
        break
    except Exception as e:
        print(f"Error reading from port: {e}")
        break

if ser.is_open:
    ser.close()
