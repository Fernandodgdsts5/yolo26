import socket
import threading
import time
import cv2
import numpy as np
import torch
from ultralytics import YOLO

from protocol import read_exactly, unpack_header, HEADER_SIZE, MAGIC_BYTES
from frame_buffer import LatestFrameBuffer, FramePacketData

def tcp_receiver_thread(host: str, port: int, buffer: LatestFrameBuffer, stop_event: threading.Event):
    """Hilo dedicado a recibir la ráfaga TCP desde Android de forma no bloqueante."""
    server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server_sock.bind((host, port))
    server_sock.listen(1)
    server_sock.settimeout(1.0)

    print(f"[TCP Server] Hilo de red escuchando en {host}:{port}...")

    while not stop_event.is_set():
        try:
            conn, addr = server_sock.accept()
        except socket.timeout:
            continue

        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        print(f"[TCP Server] ¡Cliente Android conectado desde {addr}!")

        try:
            while not stop_event.is_set():
                header_bytes = read_exactly(conn, HEADER_SIZE)
                magic, frame_id, timestamp_ms, width, height, jpeg_size = unpack_header(header_bytes)

                if magic != MAGIC_BYTES:
                    print(f"[ERROR] Magic bytes corruptos: {magic}")
                    break

                jpeg_data = read_exactly(conn, jpeg_size)

                nparr = np.frombuffer(jpeg_data, dtype=np.uint8)
                frame = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

                if frame is not None:
                    packet = FramePacketData(frame_id, timestamp_ms, width, height, jpeg_size, frame)
                    buffer.put(packet)
        except (ConnectionResetError, BrokenPipeError) as e:
            print(f"[INFO] Conexión TCP terminada: {e}")
        finally:
            conn.close()

    server_sock.close()
    print("[TCP Server] Hilo de red detenido.")

def robot_guide_controller_stub(detections: list):
    """
    HOOK FASE 7: Interfaz preparada para enviar comandos de dirección/freno
    hacia el microcontrolador ESP32 del Robot Guía Autónomo.
    """
    # Ejemplo: si se detecta 'person' a corta distancia, enviar STOP al ESP32
    for det in detections:
        cls_name = det['name']
        conf = det['conf']
        if cls_name == 'person' and conf > 0.6:
            # print("[ROBOT CONTROL] ¡Obstáculo humano detectado! Emitiendo orden STOP a ESP32")
            pass

def main():
    print("=== Yolo26 Real-Time Wi-Fi Camera Receiver & Detector ===")
    
    device = 'cuda' if torch.cuda.is_available() else 'cpu'
    print(f"Dispositivo de ejecución: {device.upper()}")

    print("Cargando modelo YOLO26s ('yolo26s.pt')...")
    model = YOLO("yolo26s.pt")
    print("¡Modelo cargado correctamente!")

    buffer = LatestFrameBuffer()
    stop_event = threading.Event()

    # Iniciar Hilo Receptor TCP de Red
    network_thread = threading.Thread(
        target=tcp_receiver_thread,
        args=("0.0.0.0", 5000, buffer, stop_event),
        daemon=True
    )
    network_thread.start()

    # Métricas de Inferencia y Rendimiento
    frame_count = 0
    inference_fps = 0.0
    last_fps_time = time.time()
    
    yolo_infer_time_ms = 0.0
    frame_age_ms = 0.0

    print("\n[MAIN] Listo para recibir video y procesar YOLO26s. Presiona 'q' para salir.\n")

    try:
        while True:
            packet, dropped_frames = buffer.get_latest()

            if packet is None:
                time.sleep(0.005)  # Evita uso del 100% de CPU en polling
                continue

            t_start = time.perf_counter()

            # Inferencia YOLO26s
            results = model.predict(source=packet.frame, conf=0.25, verbose=False, device=device)
            t_end = time.perf_counter()

            yolo_infer_time_ms = (t_end - t_start) * 1000.0
            frame_age_ms = (time.time() - packet.arrival_time) * 1000.0

            # Extraer Detecciones para el Robot Guía
            detections = []
            annotated_frame = packet.frame.copy()

            if len(results) > 0 and results[0].boxes is not None:
                for box in results[0].boxes:
                    x1, y1, x2, y2 = box.xyxy[0].cpu().numpy().astype(int)
                    conf = float(box.conf[0].cpu().numpy())
                    cls_id = int(box.cls[0].cpu().numpy())
                    cls_name = model.names.get(cls_id, str(cls_id))

                    detections.append({'name': cls_name, 'conf': conf, 'box': (x1, y1, x2, y2)})

                    # Dibujar Bounding Box
                    cv2.rectangle(annotated_frame, (x1, y1), (x2, y2), (0, 255, 0), 2)
                    label = f"{cls_name} {conf:.2f}"
                    cv2.putText(annotated_frame, label, (x1, max(y1 - 10, 20)),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 2)

            # Ejecutar Hook para Robot Autónomo
            robot_guide_controller_stub(detections)

            # Contadores de FPS
            frame_count += 1
            now = time.time()
            if now - last_fps_time >= 1.0:
                inference_fps = frame_count / (now - last_fps_time)
                frame_count = 0
                last_fps_time = now

            # Overlay de Diagnóstico Avanzado en Pantalla
            kb_size = packet.jpeg_size / 1024.0
            hud_lines = [
                f"YOLO26s [{device.upper()}] | Inf FPS: {inference_fps:.1f}",
                f"YOLO Time: {yolo_infer_time_ms:.1f} ms | Frame Age: {frame_age_ms:.1f} ms",
                f"Res: {packet.width}x{packet.height} | Size: {kb_size:.1f} KB | Dropped: {dropped_frames}",
                f"Detections: {len(detections)}"
            ]

            y_offset = 30
            for line in hud_lines:
                cv2.putText(annotated_frame, line, (10, y_offset),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 255), 2)
                y_offset += 22

            cv2.imshow("YOLO26s Low-Latency Stream (Android -> PC)", annotated_frame)

            if cv2.waitKey(1) & 0xFF == ord('q'):
                print("Finalizando programa...")
                break

    finally:
        stop_event.set()
        cv2.destroyAllWindows()

if __name__ == "__main__":
    main()
