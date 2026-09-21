import socket
import struct
from typing import Tuple

# Protocolo Binario Estricto de 32 Bytes (Big-Endian)
# 4s = Magic ('YOLO')
# Q  = Frame ID (uint64)
# Q  = Timestamp MS (uint64)
# I  = Width (uint32)
# I  = Height (uint32)
# I  = JPEG Payload Size (uint32)
HEADER_FORMAT = ">4sQQIII"
HEADER_SIZE = struct.calcsize(HEADER_FORMAT)  # 32 Bytes
MAGIC_BYTES = b"YOLO"

def read_exactly(sock: socket.socket, num_bytes: int) -> bytes:
    """
    Garantiza la lectura exacta de num_bytes desde el Socket TCP orientando a flujo.
    Evita que los paquetes truncados corrompan el decodificador JPEG.
    """
    buf = bytearray()
    while len(buf) < num_bytes:
        chunk = sock.recv(num_bytes - len(buf))
        if not chunk:
            raise ConnectionResetError("Socket cerrado por el cliente Android")
        buf.extend(chunk)
    return bytes(buf)

def unpack_header(header_bytes: bytes) -> Tuple[bytes, int, int, int, int, int]:
    """Desempaqueta el header de 32 bytes."""
    return struct.unpack(HEADER_FORMAT, header_bytes)
