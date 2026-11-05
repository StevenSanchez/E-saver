# Changelog

## 1.2 — 2026-11-05

- Lector propio de 7 segmentos para la cifra grande de kWh (22.7 y 24.9 en las
  fotos de prueba), porque ML Kit se saltaba ese número.
- Recorte de pantalla por densidad de píxeles blancos/azules: ignora el arco
  verde, el logo iluminado y los reflejos.
- Si el cargador muestra el total acumulado (por ejemplo 70.74 kWh), la app usa
  la cifra central de la sesión.
- Mejora al leer potencia cuando el OCR confunde la W con U.

## 1.1 — 2026-11-05

- Se eliminó la hora de la carga: solo se guarda la fecha y la duración.
- Registrar recibo pide únicamente periodo, monto total y kWh facturados.
- Se quitaron impuestos, bomberos, factura, medidor, lecturas y notas del recibo.
- El CSV ya no incluye la columna de hora.

## 1.0 — 2026-11-04

- Primera versión: cargas manuales, periodos de facturación configurables,
  cálculo de la parte del carro con el recibo, fotos, carro/batería, tema claro
  y oscuro, exportación CSV y respaldo local.
