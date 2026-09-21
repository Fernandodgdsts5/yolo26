import time
import cv2
import numpy as np
import torch
from ultralytics import YOLO

def main():
    print("=== FASE 1: Verificación Técnica de YOLO26s en PC ===")
    device = 'cuda' if torch.cuda.is_available() else 'cpu'
    print(f"Dispositivo de Inferencia: {device}")
    if device == 'cuda':
        print(f"GPU Detectada: {torch.cuda.get_device_name(0)}")

    print("Cargando modelo oficial 'yolo26s.pt'...")
    try:
        model = YOLO("yolo26s.pt")
        print("¡Modelo YOLO26s cargado con éxito!")
    except Exception as e:
        print(f"[ERROR] No se pudo cargar el modelo YOLO26s: {e}")
        return

    # Inferencia de prueba con imagen sintética (640x480)
    dummy_img = np.zeros((480, 640, 3), dtype=np.uint8)
    cv2.putText(dummy_img, "YOLO26s Test", (200, 240), cv2.FONT_HERSHEY_SIMPLEX, 1, (255, 255, 255), 2)

    print("Ejecutando inferencia de calentamiento (Warm-up)...")
    _ = model.predict(source=dummy_img, device=device, verbose=False)

    print("\nMidiendo tiempo de inferencia promedio (50 iteraciones)...")
    latencies = []
    for _ in range(50):
        t0 = time.perf_counter()
        results = model.predict(source=dummy_img, device=device, conf=0.25, verbose=False)
        t1 = time.perf_counter()
        latencies.append((t1 - t0) * 1000.0)

    avg_latency = np.mean(latencies)
    max_fps = 1000.0 / avg_latency
    print(f"Tiempo de inferencia promedio: {avg_latency:.2f} ms")
    print(f"FPS Máximo estimado de inferencia: {max_fps:.2f} FPS")
    print("\nFase 1 Completada con éxito.")

if __name__ == "__main__":
    main()
