import threading
import time
from typing import Optional, Tuple
import numpy as np

class FramePacketData:
    def __init__(self, frame_id: int, timestamp_ms: int, width: int, height: int, jpeg_size: int, frame: np.ndarray):
        self.frame_id = frame_id
        self.timestamp_ms = timestamp_ms
        self.width = width
        self.height = height
        self.jpeg_size = jpeg_size
        self.frame = frame
        self.arrival_time = time.time()

class LatestFrameBuffer:
    """
    Buffer atómico de ranura única (Single-Slot Buffer).
    Descarta automáticamente frames viejos no leídos cuando la inferencia YOLO
    o el procesamiento en PC tarda más que la velocidad de captura de Android.
    """
    def __init__(self):
        self._lock = threading.Lock()
        self._latest_packet: Optional[FramePacketData] = None
        self._dropped_frames_counter = 0

    def put(self, packet: FramePacketData):
        with self._lock:
            if self._latest_packet is not None:
                self._dropped_frames_counter += 1
            self._latest_packet = packet

    def get_latest(self) -> Tuple[Optional[FramePacketData], int]:
        with self._lock:
            packet = self._latest_packet
            self._latest_packet = None  # Consume el frame
            return packet, self._dropped_frames_counter
