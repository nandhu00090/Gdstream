# Synthetic black HEVC base + actual metadata-only MEL RPU. No movie data.
from pathlib import Path
import re
import sys
root=Path(__file__).resolve().parent.parent/'android/app/src/test/resources'
data=Path(sys.argv[1]).read_bytes()
nals=[x for x in re.split(b'\x00\x00\x00?\x01',data) if x]
aus=[]
for n in nals:
 if n[0]>>1 & 63 ==35: aus.append(bytearray())
 aus[-1].extend(b'\x00\x00\x01'+n)
assert len(aus)==6
rpu=b'\x7c\x01'+(root/'dovi/mel_orig.bin').read_bytes()[4:]
def crc(data):
 c=0xffffffff
 for b in data:
  c^=b<<24
  for _ in range(8): c=((c<<1)^(0x04c11db7 if c&0x80000000 else 0))&0xffffffff
 return c.to_bytes(4,'big')
cc={}
def packets(pid,data,pusi=True):
 out=b''
 while data:
  count=min(184,len(data)); adaptation=184-count
  head=bytes([0x47,((pid>>8)&31)|(64 if pusi else 0),pid&255,(0x30 if adaptation else 0x10)|cc.get(pid,0)])
  cc[pid]=(cc.get(pid,0)+1)%16
  af=bytes([adaptation-1])+ (bytes([0])+b'\xff'*(adaptation-2) if adaptation>=2 else b'') if adaptation else b''
  out+=head+af+data[:count]; data=data[count:]; pusi=False
 return out
def pts(t): return bytes([0x21|((t>>29)&14),(t>>22)&255,1|((t>>14)&254),(t>>7)&255,1|((t<<1)&254)])
def pes(payload,t): return b'\x00\x00\x01\xe0\x00\x00\x80\x80\x05'+pts(t)+payload
pat=bytes.fromhex('00b00d0001c100000001e100'); pat+=crc(pat)
pmt=bytes.fromhex('02b0170001c10000f011f00024f011f00024f015f000'); pmt+=crc(pmt)
stream=packets(0,b'\x00'+pat)+packets(0x100,b'\x00'+pmt)
# Enough null packets to permit sniff/PCR probing without truncation.
stream+=b'\x47\x1f\xff\x10'+b'\xff'*184
for index,au in enumerate(aus):
 # Decode order for B pictures: I P B B P B. Same PTS on both PIDs.
 t=90000+[0,3,2,1,5,4][index]*3750
 el=b'\x00\x00\x01\x02\x01\x80'+b'\xab'*220+b'\x00\x00\x01\x7e\x01'+b'\xcd'*99+b'\x00\x00\x01'+rpu
 # Alternate arrival order and split RPU in TS packets.
 layers=[(0x1011,bytes(au)),(0x1015,el)]
 if index%2: layers.reverse()
 for pid,payload in layers: stream+=packets(pid,pes(payload,t))
(root/'bluray/dual-dv.ts.bin').write_bytes(stream)
print(len(stream), 'bytes;',len(aus),'base AUs')
