import socket
import time
import cv2
import numpy as np
from protocol import read_exactly, unpack_header, HEADER_SIZE, MAGIC_BYTES

def start_tcp_receiver(host: str = "0.0.0.0", port: int = 5000):
    server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server_sock.bind((host, port))
    server_sock.listen(1)

    print(f"[FASE 2 TCP Receiver] Servidor escuchando en {host}:{port}...")

    while True:
        print("\nEsperando conexión del dispositivo Android...")
        conn, addr = server_sock.accept()
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        print(f"¡Conectado exitosamente con Android desde {addr}!")

        frame_count = 0
        last_fps_update = time.time()
        fps = 0.0

        try:
            while True:
                # 1. Leer header de 32 bytes
                header_bytes = read_exactly(conn, HEADER_SIZE)
                magic, frame_id, timestamp_ms, width, height, jpeg_size = unpack_header(header_bytes)

                if magic != MAGIC_BYTES:
                    print(f"[ERROR] Magic Bytes inválidos: {magic}. Desconectando cliente.")
                    break

                # 2. Leer datos raw de JPEG
                jpeg_data = read_exactly(conn, jpeg_size)

                # 3. Decodificar JPEG
                nparr = np.frombuffer(jpeg_data, dtype=np.uint8)
                frame = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

                if frame is None:
                    continue

                frame_count += 1
                now = time.time()

                if now - last_fps_update >= 1.0:
                    fps = frame_count / (now - last_fps_update)
                    frame_count = 0
                    last_fps_update = now

                # Overlay visual
                kb_size = jpeg_size / 1024.0
                info_text = f"Frame: {frame_id} | Res: {width}x{height} | Size: {kb_size:.1f} KB | FPS: {fps:.1f}"
                cv2.putText(frame, info_text, (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 0), 2)

                cv2.imshow("Android Stream - FASE 2 (Sin YOLO)", frame)

                if cv2.waitKey(1) & 0xFF == ord('q'):
                    print("Cierre solicitado por el usuario.")
                    break

        except (ConnectionResetError, BrokenPipeError) as e:
            print(f"[INFO] Cliente Android desconectado: {e}")
        finally:
            conn.close()
            cv2.destroyAllWindows()

if __name__ == "__main__":
    start_tcp_receiver()
