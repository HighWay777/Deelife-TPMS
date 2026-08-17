import wave
import math
import struct
import os

sample_rate = 44100
duration = 1.0  # seconds
volume = 32767.0

os.makedirs('app/src/main/res/raw', exist_ok=True)

with wave.open('app/src/main/res/raw/alarm_ping.wav', 'w') as wav_file:
    wav_file.setnchannels(1)
    wav_file.setsampwidth(2)
    wav_file.setframerate(sample_rate)
    
    for i in range(int(sample_rate * duration)):
        time = i / sample_rate
        # First ping at t=0, freq=1046.50 (C6)
        # Second ping at t=0.15, freq=1318.51 (E6)
        
        env1 = math.exp(-10.0 * time) if time >= 0 else 0
        val1 = math.sin(2.0 * math.pi * 1046.50 * time) * env1
        
        t2 = time - 0.15
        env2 = math.exp(-10.0 * t2) if t2 >= 0 else 0
        val2 = math.sin(2.0 * math.pi * 1318.51 * t2) * env2
        
        val = int(volume * 0.5 * (val1 + val2))
        data = struct.pack('<h', val)
        wav_file.writeframesraw(data)
print("Generated ping sound.")
