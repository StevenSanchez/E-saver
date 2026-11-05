# Arquitectura de E-saver

E-saver es una app Android de una sola pantalla principal con cuatro pestañas.
No tiene servidor: todo se guarda en el teléfono.

## Capas

```
MainActivity
  └── ESaverApp (NavHost + barra inferior)
        ├── Inicio
        ├── Cargas
        ├── Recibos
        ├── Ajustes
        ├── Editor de carga
        ├── Editor de recibo
        └── Detalle de recibo
                    │
                    ▼
              AppViewModel
        ┌───────────┼───────────┐
        ▼           ▼           ▼
  EsaverRepository SettingsStore PhotoStore
        │            │            │
     SQLite       DataStore    files/photos
```

- **MainActivity** solo configura el tema y arranca el `NavHost`.
- **AppViewModel** mantiene el estado (cargas, recibos, ajustes, periodo
  seleccionado) y expone operaciones `suspend` para guardar/borrar.
- **EsaverRepository** encapsula SQLite (`sessions`, `bills`).
- **SettingsStore** guarda ciclo de corte, tarifa, tema y datos del carro.
- **PhotoStore** comprime y conserva las fotos.
- **OcrEngine** lee la pantalla del cargador: ML Kit para el texto normal y un
  decodificador propio de 7 segmentos para el kWh grande.

## Modelo de datos

### ChargeSession

| Campo | Uso |
|---|---|
| `timestamp` | Fecha de la carga (se guarda a mediodía; la hora no importa). |
| `energyKwh` | Consumo de esa carga. |
| `durationSec` | Duración que muestra el cargador. |
| `voltage`, `currentA`, `powerKw`, `chargerMaxA` | Datos opcionales del cargador. |
| `photoPath` | Foto dentro de la app. |
| `periodKey` | Periodo calculado, por ejemplo `2026-09`. |
| `periodOverride` | Periodo forzado a mano. |

### Bill

Un recibo por periodo (`UNIQUE(period_key)`): monto total y kWh facturados.
Los datos de impuestos, lecturas o número de factura **no se piden** porque no
cambian el cálculo de la parte del carro.

## Ciclo de facturación

El día de corte es configurable (21 por defecto). Un periodo se etiqueta con el
mes en que termina:

```
Periodo 09-2026 = [21 ago 2026, 21 sep 2026)
```

Una carga del **21** pertenece al periodo que **inicia** ese día. Si el recibo
trae fechas distintas, se puede ajustar el periodo de una carga a mano. Al
cambiar el día de corte, se recalculan las cargas automáticas y se conservan los
ajustes manuales.

## Cálculo del pago

```
tarifa efectiva = total pagado ÷ kWh facturados
parte del carro = tarifa efectiva × kWh del carro en el periodo
parte de la casa = total pagado − parte del carro
```

Si no hay recibo, se usa el costo aproximado por kWh; también se puede usar el
último recibo o el promedio de recibos.

## OCR

1. Se busca la pantalla del cargador por **densidad de píxeles blancos/azules**
   en el centro de la foto. Así se ignoran el arco verde, el logo y los reflejos.
2. Se recorta, se amplía y se procesa en gris/invertido/contraste.
3. ML Kit lee duración, voltaje, corriente y potencia.
4. La cifra grande de kWh se decodifica con un lector de **7 segmentos**:
   segmenta los dígitos, evalúa los siete segmentos y arma el número (por ejemplo
   22.7 o 24.9). Si el cargador muestra además el total acumulado, se prefiere la
   cifra central de la sesión.
5. Se valida el kWh contra potencia × duración cuando esos datos existen.
6. Todo queda editable: el OCR es una ayuda, no un requisito.

## Privacidad

- Sin permiso de internet: el modelo de ML Kit va incluido en el APK.
- Sin cuentas, sin analytics, sin nube.
- Las fotos y la base de datos viven en el almacenamiento privado de la app.
- El respaldo CSV se genera localmente y se comparte con FileProvider.
